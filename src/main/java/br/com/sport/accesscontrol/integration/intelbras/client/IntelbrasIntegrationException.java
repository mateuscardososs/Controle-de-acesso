package br.com.sport.accesscontrol.integration.intelbras.client;

public class IntelbrasIntegrationException extends RuntimeException {

    public IntelbrasIntegrationException(String message) {
        super(message);
    }

    public IntelbrasIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
