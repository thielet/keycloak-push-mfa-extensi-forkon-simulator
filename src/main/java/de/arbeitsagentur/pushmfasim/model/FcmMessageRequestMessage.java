package de.arbeitsagentur.pushmfasim.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class FcmMessageRequestMessage {
    @JsonProperty("token")
    private String token;

    @JsonProperty("notification")
    private FcmMessageNotification notification;

    @JsonProperty("data")
    private FcmMessageData data;
}
