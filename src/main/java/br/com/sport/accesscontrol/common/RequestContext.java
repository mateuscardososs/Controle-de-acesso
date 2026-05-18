package br.com.sport.accesscontrol.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class RequestContext {

    public String correlationId() {
        var value = MDC.get(CorrelationId.MDC_KEY);
        return value == null ? "system" : value;
    }

    public String actorIp() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            HttpServletRequest request = servletRequestAttributes.getRequest();
            return request.getRemoteAddr();
        }
        return "system";
    }
}
