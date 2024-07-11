package org.jitsi.jigasi.transcription.config;


import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class DataClientConfig {
    private Boolean is_recording;
}
