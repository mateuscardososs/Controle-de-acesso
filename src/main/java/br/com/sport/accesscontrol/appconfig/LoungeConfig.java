package br.com.sport.accesscontrol.appconfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LoungeConfig {

    private final List<String> lounges;

    public LoungeConfig(@Value("${app.lounges:Front 1,Front 2,Front 3,Institucional 1,Institucional Vereador}") List<String> lounges) {
        this.lounges = lounges.stream().map(String::trim).toList();
    }

    public List<String> getLounges() {
        return lounges;
    }

    public boolean isValid(String value) {
        return value != null && lounges.contains(value.trim());
    }
}
