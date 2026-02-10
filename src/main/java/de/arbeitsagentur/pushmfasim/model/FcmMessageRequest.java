package de.arbeitsagentur.pushmfasim.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class FcmMessageRequest {
    private FcmMessageRequestMessage message;
}
