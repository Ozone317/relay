package com.example.relay.app.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.relay.app.api.dto.AppCreateDto;
import com.example.relay.app.api.dto.AppResponseDto;
import com.example.relay.app.domain.App;
import com.example.relay.environment.domain.Environment;

import lombok.NoArgsConstructor;

@Component
@NoArgsConstructor
public class AppMapper {

    public App toEntity(AppCreateDto dto, Environment environment) {
        return new App(
            dto.name(),
            environment
        );
    }

    public AppResponseDto toResponseDto(App app) {
        return new AppResponseDto(
            app.getId(),
            app.getName(),
            app.getEnvironment().getId(),
            app.getCreatedAt()
        );
    }

    public List<AppResponseDto> toResponseDtoList(List<App> apps) {
        return apps.stream()
        .map(this::toResponseDto)
        .toList();
    }
}
