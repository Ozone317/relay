package com.example.relay.environment.mapper;

import com.example.relay.environment.api.dto.EnvironmentCreateDto;
import com.example.relay.environment.api.dto.EnvironmentResponseDto;
import com.example.relay.environment.domain.Environment;
import com.example.relay.user.domain.User;
import java.util.List;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@NoArgsConstructor
public class EnvironmentMapper {

    public EnvironmentResponseDto toResponseDto(Environment environment) {
        return new EnvironmentResponseDto(environment.getId(), environment.getName(), environment.getDescription(),
                environment.getUpdatedAt());
    }

    public Environment toEntity(EnvironmentCreateDto createDto, User user) {
        return new Environment(createDto.name(), createDto.description(), user);
    }

    public List<EnvironmentResponseDto> toResponseDtoList(List<Environment> environments) {
        return environments.stream().map(this::toResponseDto).toList();
    }
}
