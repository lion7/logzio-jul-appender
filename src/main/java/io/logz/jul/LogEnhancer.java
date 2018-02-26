package io.logz.jul;

import io.logz.sender.com.google.gson.JsonObject;

public interface LogEnhancer {

    void enhance(JsonObject logMessage);
}
