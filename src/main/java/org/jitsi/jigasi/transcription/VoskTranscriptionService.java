/*
 * Jigasi, the JItsi GAteway to SIP.
 *
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jigasi.transcription;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.*;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.eclipse.jetty.websocket.api.*;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.eclipse.jetty.websocket.client.*;
import org.jitsi.jigasi.constant.EventWsAIEnum;
import org.jitsi.jigasi.transcription.config.ClientConfig;
import org.jitsi.jigasi.transcription.config.DataClientConfig;
import org.json.*;
import org.jitsi.jigasi.*;
import org.jitsi.utils.logging.*;

import javax.media.format.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.jivesoftware.smackx.address.MultipleRecipientManager.send;


/**
 * Implements a TranscriptionService which uses local
 * Vosk websocket transcription service.
 * <p>
 * See https://github.com/alphacep/vosk-server for
 * information about server
 *
 * @author Nik Vaessen
 * @author Damian Minkov
 * @author Nickolay V. Shmyrev
 */
public class VoskTranscriptionService
        extends AbstractTranscriptionService
{

    /**
     * The logger for this class
     */
    private final static Logger logger
            = Logger.getLogger(VoskTranscriptionService.class);

    /**
     * The config key of the websocket to the speech-to-text service.
     */
    public final static String WEBSOCKET_URL
            = "org.jitsi.jigasi.transcription.vosk.websocket_url";

    public final static String DEFAULT_WEBSOCKET_URL = "ws://103.252.1.138:18181/streaming/571d0607-0e69-4a36-ad6c-8b63090d0f9a";
    //public final static String DEFAULT_WEBSOCKET_URL = "ws://localhost:2700";
    private final static String EOF_MESSAGE = "{\"eof\" : 1}";

    private CountDownLatch latch = new CountDownLatch(1);

    /**
     * The config value of the websocket to the speech-to-text service.
     */
    private String websocketUrlConfig;

    /**
     * The URL of the websocket to the speech-to-text service.
     */
    private String websocketUrl;

    /**
     * Assigns the websocketUrl to use to websocketUrl by reading websocketUrlConfig;
     */
    private void generateWebsocketUrl(Participant participant)
        throws org.json.simple.parser.ParseException
    {
//        if (!supportsLanguageRouting())
//        {
//            websocketUrl = websocketUrlConfig;
//            return;
//        }
//        logger.info(websocketUrlConfig);
//        logger.info("participant "+ participant.getDebugName());
//
//        org.json.simple.parser.JSONParser jsonParser = new org.json.simple.parser.JSONParser();
//        Object obj = jsonParser.parse(websocketUrlConfig);
//        org.json.simple.JSONObject languageMap = (org.json.simple.JSONObject) obj;
//        String language = participant.getSourceLanguage() != null ? participant.getSourceLanguage() : "en";
//        Object urlObject = languageMap.get(language);
//        if (!(urlObject instanceof String))
//        {
//            logger.error("No websocket URL configured for language " + language);
//            websocketUrl = null;
//            return;
//        }
//        websocketUrl = (String) urlObject;
        websocketUrl = "ws://103.252.1.138:18181/streaming/" + participant.getRoomId();
        logger.info("ws: " + websocketUrl);
    }

    /**
     * Create a TranscriptionService which will send audio to the VOSK service
     * platform to get a transcription
     */
    public VoskTranscriptionService()
    {
        websocketUrlConfig = JigasiBundleActivator.getConfigurationService()
                .getString(WEBSOCKET_URL, DEFAULT_WEBSOCKET_URL);
    }

    /**
     * No configuration required yet
     */
    public boolean isConfiguredProperly()
    {
        return true;
    }

    /**
     * If the websocket url is a JSON, language routing is supported
     */
    public boolean supportsLanguageRouting()
    {
        return websocketUrlConfig.trim().startsWith("{");
    }

    /**
     * Sends audio as an array of bytes to Vosk service
     *
     * @param request        the TranscriptionRequest which holds the audio to be sent
     * @param resultConsumer a Consumer which will handle the
     *                       TranscriptionResult
     */
    @Override
    public void sendSingleRequest(final TranscriptionRequest request,
                                  final Consumer<TranscriptionResult> resultConsumer)
    {
        // Try to create the client, which can throw an IOException
        try
        {
            // Set the sampling rate and encoding of the audio
            AudioFormat format = request.getFormat();
            if (!format.getEncoding().equals("LINEAR"))
            {
                throw new IllegalArgumentException("Given AudioFormat" +
                        "has unexpected" +
                        "encoding");
            }
            Instant timeRequestReceived = Instant.now();

            WebSocketClient ws = new WebSocketClient();
            VoskWebsocketSession socket = new VoskWebsocketSession(request);
            ws.start();
            ws.connect(socket, new URI(websocketUrl));
            //ws.connect(socket, new URI(websocketUrl));
            socket.awaitClose();
            resultConsumer.accept(
                    new TranscriptionResult(
                            null,
                            UUID.randomUUID(),
                            timeRequestReceived,
                            false,
                            request.getLocale().toLanguageTag(),
                            0,
                            new TranscriptionAlternative(socket.getResult())));
        }
        catch (Exception e)
        {
            logger.error("Error sending single req", e);
        }
    }

    @Override
    public StreamingRecognitionSession initStreamingSession(Participant participant)
        throws UnsupportedOperationException
    {
        try
        {
            generateWebsocketUrl(participant);
            VoskWebsocketStreamingSession streamingSession = new VoskWebsocketStreamingSession(
                    participant.getDebugName());
            streamingSession.transcriptionTag = participant.getTranslationLanguage();
            if (streamingSession.transcriptionTag == null)
            {
                streamingSession.transcriptionTag = participant.getSourceLanguage();
            }
            return streamingSession;
        }
        catch (Exception e)
        {
            throw new UnsupportedOperationException("Failed to create streaming session", e);
        }
    }

    @Override
    public boolean supportsFragmentTranscription()
    {
        return true;
    }

    @Override
    public boolean supportsStreamRecognition()
    {
        return true;
    }

    /**
     * A Transcription session for transcribing streams, handles
     * the lifecycle of websocket
     */
    @WebSocket
    public class VoskWebsocketStreamingSession
        implements StreamingRecognitionSession
    {
        private Session session;
        /* The name of the participant */
        private final String debugName;
        /* The sample rate of the audio stream we collect from the first request */
        private double sampleRate = -1.0;
        /* Last returned result so we do not return the same string twice */
        private String lastResult = "";
        /* Transcription language requested by the user who requested the transcription */
        private String transcriptionTag = "en-US";

        /**
         * List of TranscriptionListeners which will be notified when a
         * result comes in
         */
        private final List<TranscriptionListener> listeners = new ArrayList<>();

        /**
         *  Latest assigned UUID to a transcription result.
         *  A new one has to be generated whenever a definitive result is received.
         */
        private UUID uuid = UUID.randomUUID();

        VoskWebsocketStreamingSession(String debugName)
            throws Exception
        {
            this.debugName = debugName;
            WebSocketClient ws = new WebSocketClient();
            ws.start();
            ws.connect(this, new URI(websocketUrl));
            //ws.connect(this, new URI(websocketUrl));
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            this.session = null;
        }

        @OnWebSocketConnect
        public void onConnect(Session session)
        {
            try {
                latch.countDown();
                logger.info("opened connection");
                ObjectMapper objectMapper = new ObjectMapper();

                ClientConfig clientConfig = ClientConfig
                        .builder()
                        .type(EventWsAIEnum.EVENT_RECEIVE_CLIENT_CONFIG.getName())
                        .data(DataClientConfig
                                .builder()
                                .is_recording(true)
                                .build())
                        .build();
                String json = objectMapper.writeValueAsString(clientConfig);
                session.getRemote().sendString(json);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            this.session = session;
        }

        @OnWebSocketMessage
        public void onMessage(String msg)
        {
            boolean partial = true;
            String result = "";
            if (logger.isDebugEnabled())
                logger.debug(debugName + "Recieved response: " + msg);
            JSONObject jsonObject = new JSONObject(msg);
            String message = "";
            try{
                // Lấy đối tượng "data" từ JSON chính
                JSONObject dataObject = jsonObject.getJSONObject("data");

                // Lấy giá trị "predict_segment" từ đối tượng "data"
                message = dataObject.getString("predict_segment");

                logger.info(message);
            }catch (Exception e){
                //logger.info(msg);
            }

            JSONObject obj = new JSONObject("{\"partial\" : \"" + message + "\"}");
            if (obj.has("partial"))
            {
                result = obj.getString("partial");
            }
            else
            {
                partial = false;
                result = obj.getString("text");
            }

            if (!result.isEmpty() && (!partial || !result.equals(lastResult)))
            {
                lastResult = result;
                for (TranscriptionListener l : listeners)
                {
                    l.notify(new TranscriptionResult(
                            null,
                            uuid,
                            // this time needs to be the one when the audio was sent
                            // the results need to be matched with the time when we sent the audio, so we have
                            // the real time when this transcription was started
                            Instant.now(),
                            partial,
                            transcriptionTag,
                            1.0,
                            new TranscriptionAlternative(result)));
                }
            }

            if (!partial)
            {
                this.uuid = UUID.randomUUID();
            }
        }

        @OnWebSocketError
        public void onError(Throwable cause)
        {
            logger.error("Error while streaming audio data to transcription service" , cause);
        }

        public void sendRequest(TranscriptionRequest request)
        {
            try
            {
//                if (sampleRate < 0)
//                {
//                    sampleRate = request.getFormat().getSampleRate();
//                    session.getRemote().sendString("{\"config\" : {\"sample_rate\" : " + sampleRate + " }}");
//                }
//                ByteBuffer audioBuffer = ByteBuffer.wrap(request.getAudio());
//                session.getRemote().sendBytes(audioBuffer);

                BsonDocument document = new BsonDocument();
                document.put("type", new BsonString(EventWsAIEnum.EVENT_RECEIVE_ADMIN_PUSH_AUDIO.getName()));
                BsonDocument data = new BsonDocument();
                data.put("blob_data", new BsonBinary(request.getAudio()));
                data.put("is_end_streaming", new BsonBoolean(false));
                data.put("segment_id", new BsonString(String.valueOf(0)));
                document.put("data", data);
                BasicOutputBuffer buffer = new BasicOutputBuffer();
                BsonDocumentCodec codec = new BsonDocumentCodec();
                codec.encode(new BsonBinaryWriter(buffer), document, EncoderContext.builder().isEncodingCollectibleDocument(true).build());
                byte[] serializedData = buffer.toByteArray();
                session.getRemote().sendBytes(ByteBuffer.wrap(serializedData));

            }
            catch (Exception e)
            {
                logger.error("Error to send websocket request for participant " + debugName, e);
            }
        }

        public void addTranscriptionListener(TranscriptionListener listener)
        {
            listeners.add(listener);
        }

        public void end()
        {
            try
            {
                session.getRemote().sendString(EOF_MESSAGE);
            }
            catch (Exception e)
            {
                logger.error("Error to finalize websocket connection for participant " + debugName, e);
            }
        }

        public boolean ended()
        {
            return session == null;
        }
    }

    /**
     * Session to send websocket data and recieve results. Non-streaming version
     */
    @WebSocket
    public class VoskWebsocketSession
    {
        /* Signal for the end of operation */
        private final CountDownLatch closeLatch;

        /* Request we need to process */
        private final TranscriptionRequest request;

        /* Collect results*/
        private StringBuilder result;

        VoskWebsocketSession(TranscriptionRequest request)
        {
            this.closeLatch = new CountDownLatch(1);
            this.request = request;
            this.result = new StringBuilder();
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            this.closeLatch.countDown(); // trigger latch
        }

        @OnWebSocketConnect
        public void onConnect(Session session)
        {
            try
            {
//                AudioFormat format = request.getFormat();
//                session.getRemote().sendString("{\"config\" : {\"sample_rate\" : " + format.getSampleRate() + "}}");
//                ByteBuffer audioBuffer = ByteBuffer.wrap(request.getAudio());
//                session.getRemote().sendBytes(audioBuffer);
//                session.getRemote().sendString(EOF_MESSAGE);

                latch.countDown();
                //log.info("opened connection");
                ObjectMapper objectMapper = new ObjectMapper();

                ClientConfig clientConfig = ClientConfig
                        .builder()
                        .type(EventWsAIEnum.EVENT_RECEIVE_CLIENT_CONFIG.getName())
                        .data(DataClientConfig
                                .builder()
                                .is_recording(true)
                                .build())
                        .build();
                String json = objectMapper.writeValueAsString(clientConfig);
                session.getRemote().sendString(json);

//                BsonDocument document = new BsonDocument();
//                document.put("type", new BsonString(EventWsAIEnum.EVENT_RECEIVE_ADMIN_PUSH_AUDIO.getName()));
//                BsonDocument data = new BsonDocument();
//                data.put("blob_data", new BsonBinary(request.getAudio()));
//                data.put("is_end_streaming", new BsonBoolean(false));
//                data.put("segment_id", new BsonString(String.valueOf(0)));
//                document.put("data", data);
//                BasicOutputBuffer buffer = new BasicOutputBuffer();
//                BsonDocumentCodec codec = new BsonDocumentCodec();
//                codec.encode(new BsonBinaryWriter(buffer), document, EncoderContext.builder().isEncodingCollectibleDocument(true).build());
//                byte[] serializedData = buffer.toByteArray();
//                session.getRemote().sendBytes(ByteBuffer.wrap(serializedData));
            }
            catch (IOException e)
            {
                logger.error("Error to transcribe audio", e);
            }
        }

        @OnWebSocketMessage
        public void onMessage(String msg)
        {
            logger.info("log duoi" + msg);
            result.append("text{\"partial\" : \"" + "Long day" + "\"}");
            //result.append("text{\"partial\" : \"" + msg + "\"}");
            result.append('\n');
        }

        @OnWebSocketError
        public void onError(Throwable cause)
        {
            logger.error("Websocket connection error", cause);
        }

        public String getResult()
        {
            return result.toString();
        }

        void awaitClose()
            throws InterruptedException
        {
            closeLatch.await();
        }
    }

}
