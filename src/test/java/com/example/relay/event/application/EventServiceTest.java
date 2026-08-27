package com.example.relay.event.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.relay.app.domain.App;
import com.example.relay.app.exception.AppNotFoundException;
import com.example.relay.app.infrastructure.AppRepository;
import com.example.relay.environment.domain.Environment;
import com.example.relay.event.api.dto.EventCreateDto;
import com.example.relay.event.domain.Event;
import com.example.relay.event.exception.EventAlreadyExistsException;
import com.example.relay.event.infrastructure.EventRepository;
import com.example.relay.event.mapper.EventMapper;
import com.example.relay.user.domain.User;

@ExtendWith(MockitoExtension.class)
public class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private AppRepository appRepository;

    @Mock
    private EventMapper eventMapper;

    @InjectMocks
    private EventService underTest;

    @Test
    void create_savesEventUnderApp_whenAppBelongsToUserAndTheGivenEnvironment() throws Exception {
        // Arrange
        User user = new User("user@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        Event event = new Event("payment.created", app);
        EventCreateDto request = new EventCreateDto("payment.created");

        // Stubs
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId())).thenReturn(Optional.of(app));
        when(eventMapper.toEntity(request, app)).thenReturn(event);
        when(eventRepository.saveAndFlush(event)).thenReturn(event);

        // Act
        Event result = underTest.create(request, app.getId(), env.getId(), user.getId());

        // Assert
        assertEquals(request.name(), result.getName());
        assertEquals(app.getId(), result.getApp().getId());
        assertEquals(user.getId(), result.getApp().getEnvironment().getUser().getId());
    }

    @Test
    void create_throwsAppNotFoundException_whenAppDoesNotExistOrDoesNotBelongToUser() throws Exception {
        // Arrange
        User user = new User("user@mail.com", "someHash");
        User differentUser = new User("diff@mail.com", "someOtherHash");
        Environment env = new Environment("Env 1", "Desc 1", differentUser);
        App app = new App("App 1", env);
        EventCreateDto request = new EventCreateDto("payment.created");

        // Stub
        doThrow(new AppNotFoundException(app.getId())).when(appRepository).findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId());

        // Act + Assert
        assertThrows(AppNotFoundException.class, () -> underTest.create(request, app.getId(), env.getId(), user.getId()));

        // Verify
        verify(appRepository).findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId());
    }

    @Test
    void create_throwsAppNotFoundException_whenAppBelongsToUserButNotToTheGivenEnvironment() throws Exception {
        // Arrange
        User user = new User("user@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        EventCreateDto request = new EventCreateDto("payment.created");

        UUID wrongEnvId = UUID.randomUUID();

        // Stub
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), wrongEnvId, user.getId())).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(AppNotFoundException.class, () -> underTest.create(request, app.getId(), wrongEnvId, user.getId()));
    }

    @Test
    void create_throwsEventAlreadyExistsException_whenEventWithSameNameAlreadyExists() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        EventCreateDto request = new EventCreateDto("payment.created");
        Event existingEvent = new Event("payment.created", app);

        // Stub
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId())).thenReturn(Optional.of(app));
        when(eventRepository.findByNameAndAppId(request.name(), app.getId())).thenReturn(Optional.of(existingEvent));

        // Act + Assert
        assertThrows(EventAlreadyExistsException.class, () -> underTest.create(request, app.getId(), env.getId(), user.getId()));

        // Verify
        verify(appRepository).findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId());
    }

    @Test
    void create_throwsDataIntegrityViolationException_whenTwoRequestsRaceToCreateEventWithSameName() throws Exception {
        // Arrange
        User user = new User("test@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        EventCreateDto request = new EventCreateDto("payment.created");
        Event event = new Event("payment.created", app);

        // Stub
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId())).thenReturn(Optional.of(app));
        when(eventRepository.findByNameAndAppId(request.name(), app.getId())).thenReturn(Optional.empty());
        when(eventMapper.toEntity(request, app)).thenReturn(event);
        doThrow(new DataIntegrityViolationException(null)).when(eventRepository).saveAndFlush(event);

        // Act + Assert
        assertThrows(EventAlreadyExistsException.class, () -> underTest.create(request, app.getId(), env.getId(), user.getId()));
    }

    @Test
    void getAll_returnsAllEventsUnderApp_whenAppExistsAndBelongsToExistingUserAndTheGivenEnvironment() throws Exception {
        // Arrange
        User user = new User("user@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);
        List<Event> events = List.of(
            new Event("payment.created", app),
            new Event("user.created", app)
        );

        // Stub
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), env.getId(), user.getId())).thenReturn(Optional.of(app));
        when(eventRepository.findAllByAppId(app.getId())).thenReturn(events);

        // Act
        List<Event> result = underTest.getAll(app.getId(), env.getId(), user.getId());

        // Assert
        assertEquals(events.size(), result.size());
        for (int i = 0; i < events.size(); i++) {
            assertEquals(events.get(i).getId(), result.get(i).getId());
            assertEquals(events.get(i).getName(), result.get(i).getName());
            assertEquals(events.get(i).getCreatedAt(), result.get(i).getCreatedAt());
            assertEquals(events.get(i).getApp().getId(), result.get(i).getApp().getId());
        }
    }

    @Test
    void getAll_throwsAppNotFoundException_whenAppBelongsToUserButNotToTheGivenEnvironment() throws Exception {
        // Arrange
        User user = new User("user@mail.com", "someHash");
        Environment env = new Environment("Env 1", "Desc 1", user);
        App app = new App("App 1", env);

        UUID wrongEnvId = UUID.randomUUID();

        // Stub
        when(appRepository.findByIdAndEnvironmentIdAndEnvironmentUserId(app.getId(), wrongEnvId, user.getId())).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(AppNotFoundException.class, () -> underTest.getAll(app.getId(), wrongEnvId, user.getId()));
    }
}
