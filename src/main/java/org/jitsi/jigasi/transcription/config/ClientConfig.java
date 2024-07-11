package org.jitsi.jigasi.transcription.config;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class ClientConfig {
    private String type;
    private DataClientConfig data;
}
