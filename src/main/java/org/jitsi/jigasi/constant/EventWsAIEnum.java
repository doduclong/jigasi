package org.jitsi.jigasi.constant;
public enum EventWsAIEnum {
    EVENT_AI_USER_UTTERED("user_uttered", "user_uttered"),
    EVENT_AI_USER_CONFIG("user_config", "user_config"),
    EVENT_AI_ASR_PREDICT("asr_uttered", "asr_uttered"),
    EVENT_RECEIVE_ADMIN_PUSH_AUDIO("admin_push_audio", "admin_push_audio"),
    EVENT_RECEIVE_CLIENT_CONFIG("client_config", "client_config"),
    SYSTEM_RETURN_ASR_PREDICT("system_return_asr_predict", "system_return_asr_predict"),
    SYSTEM_RETURN_ASR_NORMALIZE("system_return_asr_normalize", "system_return_asr_normalize"),
    SYSTEM_RETURN_DATA_UPDATE("system_return_data_update", "system_return_data_update");


    private String name;
    private String description;

    public String getName(){
        return name;
    }

    EventWsAIEnum(String name, String description) {
        this.name = name;
        this.description = description;
    }
}
