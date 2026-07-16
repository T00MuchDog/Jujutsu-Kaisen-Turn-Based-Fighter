package com.jjktbf.graphics.multiplayer;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

final class NetworkJson {
    private NetworkJson() {
    }

    static ObjectMapper newMapper() {
        return JsonMapper.builder()
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    }
}
