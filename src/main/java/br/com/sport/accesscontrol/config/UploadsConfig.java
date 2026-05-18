package br.com.sport.accesscontrol.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class UploadsConfig implements WebMvcConfigurer {

    private final String facesDir;

    public UploadsConfig(@Value("${app.uploads.faces-dir:uploads/faces}") String facesDir) {
        this.facesDir = facesDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        var location = Path.of(facesDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/faces/**")
                .addResourceLocations(location);
    }
}
