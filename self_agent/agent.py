import asyncio
import json
import logging
import uuid
from typing import AsyncGenerator, AsyncIterable
from dotenv import load_dotenv

from livekit import agents, rtc
from livekit.agents import AgentServer, AgentSession, Agent, inference, tokenize
from livekit.agents.voice import room_io
from livekit.agents.voice.agent import ModelSettings

load_dotenv(".env.local")
load_dotenv(".env")

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("agent")


class Assistant(Agent):
    def __init__(self, send_sentence_fn=None) -> None:
        super().__init__(
            instructions="""You are a helpful voice AI assistant.
            You eagerly assist users with their questions by providing information from your extensive knowledge.
            Your responses are concise, to the point, and without any complex formatting or punctuation including emojis, asterisks, or other symbols.
            You are curious, friendly, and have a sense of humor.""",
        )
        self.send_sentence = send_sentence_fn

    async def tts_node(
        self, text: AsyncIterable[str], model_settings: ModelSettings
    ) -> AsyncGenerator[rtc.AudioFrame, None]:
        """Intercept LLM output tokens, stream complete sentences to mobile app for on-device TTS,
        and generate audio frames via the standard LiveKit TTS engine.
        """
        turn_id = f"turn_{uuid.uuid4().hex[:6]}"
        sentence_idx = 0
        tokenizer = tokenize.basic.SentenceTokenizer()
        sent_stream = tokenizer.stream()

        async def _pump_sentences():
            nonlocal sentence_idx
            try:
                async for token_data in sent_stream:
                    s = token_data.token.strip()
                    if s and self.send_sentence:
                        self.send_sentence(s, turn_id, sentence_idx, False)
                        sentence_idx += 1
            except asyncio.CancelledError:
                pass
            except Exception as e:
                logger.error(f"Error in sentence streaming pump: {e}")

        pump_task = asyncio.create_task(_pump_sentences())

        async def _forward_text():
            try:
                async for chunk in text:
                    sent_stream.push_text(chunk)
                    yield chunk
            finally:
                sent_stream.end_input()
                try:
                    await pump_task
                except asyncio.CancelledError:
                    pass
                if self.send_sentence:
                    self.send_sentence("", turn_id, sentence_idx, True)

        try:
            async for frame in Agent.default.tts_node(self, _forward_text(), model_settings):
                yield frame
        finally:
            if not pump_task.done():
                pump_task.cancel()

    async def stt_node(
        self, audio: AsyncIterable[rtc.AudioFrame], model_settings: ModelSettings
    ):
        """Safely forward audio frames to STT engine without allowing unhandled exceptions to crash the worker."""
        try:
            async for event in Agent.default.stt_node(self, audio, model_settings):
                yield event
        except Exception as e:
            logger.warning(f"Handled error in stt_node: {e}")


server = AgentServer()


@server.rtc_session(agent_name="my-agent")
async def my_agent(ctx: agents.JobContext):
    def send_sentence(sentence: str, turn_id: str, index: int, is_final: bool):
        try:
            packet = json.dumps({
                "type": "assistant_sentence",
                "text": sentence,
                "turn_id": turn_id,
                "index": index,
                "is_final": is_final
            })
            if sentence or is_final:
                if sentence:
                    logger.info(f"[STREAMING-TTS] Sent sentence #{index} [turn={turn_id}]: '{sentence}'")
                asyncio.create_task(
                    ctx.room.local_participant.publish_data(packet.encode("utf-8"))
                )
        except Exception as e:
            logger.error(f"Error publishing streaming sentence packet: {e}")

    inner_tts = inference.TTS(
        model="inworld/inworld-tts-2",
        voice="Ashley",
    )

    # Full voice pipeline with LiveKit STT (Deepgram Nova-3), LLM (Gemma 4), and Inworld TTS
    session = AgentSession(
        stt=inference.STT(model="deepgram/nova-3"),
        llm=inference.LLM(model="google/gemma-4-31b-it"),
        tts=inner_tts,
    )

    agent = Assistant(send_sentence_fn=send_sentence)

    await session.start(
        room=ctx.room,
        agent=agent,
        room_output_options=room_io.RoomOutputOptions(transcription_enabled=True),
    )

    # Log user STT transcripts from LiveKit STT
    @session.on("user_input_transcribed")
    def on_user_input_transcribed(ev):
        logger.info(f"LiveKit STT transcribed user speech: '{ev.transcript}' (is_final={ev.is_final})")

    # Accept incoming text from participant (e.g. mobile app SpeechRecognizer or interrupt signals)
    @ctx.room.on("data_received")
    def on_data_received(data_packet: rtc.DataPacket):
        asyncio.create_task(handle_data(data_packet))

    async def handle_data(data_packet: rtc.DataPacket):
        try:
            text = data_packet.data.decode("utf-8").strip()
            if not text:
                return

            participant_name = (
                data_packet.participant.identity
                if data_packet.participant
                else "Participant"
            )

            # Check if this is an explicit interrupt signal from client
            if text == "__INTERRUPT__":
                logger.info(f"Received interrupt signal from {participant_name}. Interrupting agent speech...")
                try:
                    await session.interrupt(force=True)
                    logger.info("Agent speech successfully interrupted.")
                except Exception as e:
                    logger.warning(f"Error interrupting agent: {e}")
                return

            logger.info(f"Received text input from {participant_name}: '{text}'")

            # If agent is currently speaking when new text arrives, auto-interrupt first
            if session.current_speech is not None:
                logger.info("Agent is currently speaking while new input arrived; auto-interrupting previous response.")
                try:
                    await session.interrupt(force=True)
                except Exception as e:
                    logger.warning(f"Error auto-interrupting on new input: {e}")

            # Feed text directly into the agent's LLM -> TTS pipeline
            await session.generate_reply(user_input=text)
        except Exception as e:
            logger.error(f"Error handling incoming data packet: {e}", exc_info=True)

    # Initial greeting from the agent
    await session.generate_reply(
        instructions="Greet the user and offer your assistance."
    )


if __name__ == "__main__":
    agents.cli.run_app(server)