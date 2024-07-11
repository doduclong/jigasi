package org.jitsi.jigasi.transcription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bson.*;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.glassfish.jersey.client.ClientConfig;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.jitsi.jigasi.constant.EventWsAIEnum;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

public class CustomTranscriptionService extends WebSocketClient {
    private UUID timeSheetId;
    private SimpMessagingTemplate simpMessagingTemplate;
    private ISpeechToTextContent speechToTextRepository;
    private Jedis jedis;
    private MinioUtil minioUtil;
    private CountDownLatch latch = new CountDownLatch(1);
    private int count;
    private String valueCreatePathSaveAudio;

    public CustomTranscriptionService(URI serverURI, UUID timeSheetId, SimpMessagingTemplate simpMessagingTemplate, ISpeechToTextContent speechToTextRepository, Jedis jedis, MinioUtil minioUtil, int count, String valueCreatePathSaveAudio) {
        super(serverURI);
        this.timeSheetId = timeSheetId;
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.speechToTextRepository = speechToTextRepository;
        this.jedis = jedis;
        this.minioUtil = minioUtil;
        this.count = count;
        this.valueCreatePathSaveAudio = valueCreatePathSaveAudio;
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        try {
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
            send(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void disconnect() {
        try {
            closeBlocking();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void sendAudioToAI(AudioSendToVoiceAI audioSendToVoiceAI) {
        try {
            latch.await();
            BsonDocument document = new BsonDocument();
            document.put("type", new BsonString(EventWsAIEnum.EVENT_RECEIVE_ADMIN_PUSH_AUDIO.getName()));

            BsonDocument data = new BsonDocument();
            data.put("blob_data", new BsonBinary(audioSendToVoiceAI.getData().getBlob_data()));
            data.put("is_end_streaming", new BsonBoolean(audioSendToVoiceAI.getData().getIs_end_streaming()));
            data.put("segment_id", new BsonString(String.valueOf(audioSendToVoiceAI.getData().getSegment_id())));
            document.put("data", data);
            BasicOutputBuffer buffer = new BasicOutputBuffer();
            BsonDocumentCodec codec = new BsonDocumentCodec();

            codec.encode(new BsonBinaryWriter(buffer), document, EncoderContext.builder().isEncodingCollectibleDocument(true).build());
            byte[] serializedData = buffer.toByteArray();

            send(serializedData);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onMessage(String s) {
        try {
            //log.info("received message: " + s);

            JsonObject jsonObject = JsonParser.parseString(s).getAsJsonObject();
            String type = String.valueOf(jsonObject.get("type"));
            switch (type.replace("\"", "")) {
                case "system_return_asr_predict":
                    SystemReturnAsrPredictDTO systemReturnAsrPredictDTO = parseData(s, SystemReturnAsrPredictDTO.class);
                    simpMessagingTemplate.convertAndSend("/topic/speech-to-text/predict-data/" + timeSheetId, systemReturnAsrPredictDTO);
                    //log.info("push system_return_asr_predict: " + systemReturnAsrPredictDTO.getData().getPredict_segment());
                    break;
                case "system_return_data_update":
                    SystemReturnDataUpdateDTO systemReturnDataUpdateDTO = parseData(s, SystemReturnDataUpdateDTO.class);
                    int sizeData = systemReturnDataUpdateDTO.getData().size();
                    List<NormDataSendClient> normDataSendClients = new ArrayList<>();

                    if(count == sizeData && count > 0) {
                        DataUpdate dataUpdate = systemReturnDataUpdateDTO.getData().get(count - 1);
                        SpeechToText speechToText = speechToTextRepository.getLastData(timeSheetId);

                        String content = setContentSpeechToText(speechToText, dataUpdate);
                        speechToText.setText(content);
                        speechToText = speechToTextRepository.save(speechToText);

                        addNormDataSendClient(speechToText, normDataSendClients);

                    } else {
                        for(int i=count ; i<sizeData; i++) {
                            if(i == count) {
                                SpeechToText speechToText = speechToTextRepository.getLastData(timeSheetId);
                                DataUpdate data = systemReturnDataUpdateDTO.getData().get(i);
                                if(speechToText != null && speechToText.getStartText().equals(data.getStart())) {
                                    String content = setContentSpeechToText(speechToText, data);
                                    speechToText.setText(content);
                                    speechToText = speechToTextRepository.save(speechToText);

                                    addNormDataSendClient(speechToText, normDataSendClients);
                                } else {
                                    SpeechToText newSpeechToText = SpeechToText.builder()
                                            .speakerName(data.getDelegate_text())
                                            .speakerId(data.getDelegate())
                                            .timeSheetId(timeSheetId)
                                            .pathFile("/" + timeSheetId + "/" + valueCreatePathSaveAudio)
                                            .build();
                                    String content = setContentSpeechToText(newSpeechToText, data);
                                    newSpeechToText.setText(content);

                                    newSpeechToText = speechToTextRepository.save(newSpeechToText);
                                    addNormDataSendClient(newSpeechToText, normDataSendClients);
                                }
                            } else {
                                DataUpdate data = systemReturnDataUpdateDTO.getData().get(i);
                                SpeechToText speechToText = SpeechToText.builder()
                                        .speakerName(data.getDelegate_text())
                                        .speakerId(data.getDelegate())
                                        .timeSheetId(timeSheetId)
                                        .pathFile("/" + timeSheetId + "/" + valueCreatePathSaveAudio)
                                        .build();
                                String content = setContentSpeechToText(speechToText, data);
                                speechToText.setText(content);
                                speechToText = speechToTextRepository.save(speechToText);
                                addNormDataSendClient(speechToText, normDataSendClients);
                            }
                        }
                        count = sizeData;
                    }
                    if(!normDataSendClients.isEmpty()) {
                        simpMessagingTemplate.convertAndSend("/topic/speech-to-text/normalize-data/" + timeSheetId, normDataSendClients);
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String setContentSpeechToText(SpeechToText speechToText, DataUpdate dataUpdate) {
        String content = "";
        List<Word> words = dataUpdate.getWords();
        for (int i=0 ; i<words.size(); i ++) {
            if(i == 0) speechToText.setStartText(words.get(i).getStart());
            if(i == words.size()-1) speechToText.setEndText(words.get(i).getEnd());
            content += words.get(i).getContent();
        };

        return content;
    }

    public void addNormDataSendClient(SpeechToText speechToText, List<NormDataSendClient> normDataSendClients) {
        NormDataSendClient normDataSendClient = NormDataSendClient.builder()
                .id(speechToText.getId().toString())
                .text(speechToText.getText())
                .speakerId(speechToText.getSpeakerId())
                .speakerName(speechToText.getSpeakerName())
                .startText(speechToText.getStartText())
                .endText(speechToText.getEndText())
                .build();
        normDataSendClients.add(normDataSendClient);
    }

    @Override
    public void onClose(int i, String s, boolean b) {
        jedis.del(timeSheetId.toString());
        //log.info("Connection closed");
    }

    @Override
    public void onError(Exception e) {

        //log.info(e.getMessage());
    }

    public <T> T parseData(String data, Class<T> clazz) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(data, clazz);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
