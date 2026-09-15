package com.example.relay.user.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class UserRepositoryActivationPostgresTest implements SharedPostgresContainer {

    @Autowired
    private UserRepository userRepository;

    private User user;

    @AfterEach
    void cleanUp() {
        if (user != null) {
            userRepository.delete(user);
        }
    }

    @Test
    @Transactional
    void activateIfPending_updatesPasswordAndActivates_whenStillPending() {
        user = userRepository.saveAndFlush(new User("pending-activate@example.com", "provisional-hash"));
        long versionBefore = user.getVersion();

        int updated = userRepository.activateIfPending(user.getId(), "new-hash");

        assertEquals(1, updated);
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(reloaded.isEmailVerified());
        assertEquals("new-hash", reloaded.getPasswordHash());
        assertTrue(reloaded.getVersion() > versionBefore, "version must be bumped by the atomic update");

        // Refresh the class field to the post-update version before cleanUp() runs - it still holds
        // the pre-activation, now-stale version and would otherwise itself risk an
        // OptimisticLockException on delete().
        user = reloaded;
    }

    @Test
    @Transactional
    void activateIfPending_doesNothing_whenAlreadyActive() {
        user = userRepository.saveAndFlush(new User("already-active@example.com", "original-hash"));
        user.markEmailVerified();
        user = userRepository.saveAndFlush(user);

        int updated = userRepository.activateIfPending(user.getId(), "attacker-hash");

        assertEquals(0, updated);
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertEquals("original-hash", reloaded.getPasswordHash(), "an already-active row must not be touched");
    }

    @Test
    @Transactional
    void setPasswordOnly_updatesPasswordAndNeverTouchesEmailVerified_whenPending() {
        user = userRepository.saveAndFlush(new User("pending-setpw@example.com", "provisional-hash"));

        int updated = userRepository.setPasswordOnly(user.getId(), "new-hash");

        assertEquals(1, updated);
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertEquals("new-hash", reloaded.getPasswordHash());
        assertTrue(!reloaded.isEmailVerified(), "setPasswordOnly must never activate the account");

        // Refresh the class field to the post-update version before cleanUp() runs - it still holds
        // the pre-update, now-stale version and would otherwise itself risk an
        // OptimisticLockException on delete().
        user = reloaded;
    }

    @Test
    @Transactional
    void setPasswordOnly_updatesPassword_whenAlreadyActive() {
        user = userRepository.saveAndFlush(new User("active-setpw@example.com", "old-hash"));
        user.markEmailVerified();
        user = userRepository.saveAndFlush(user);

        int updated = userRepository.setPasswordOnly(user.getId(), "new-hash");

        assertEquals(1, updated);
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(reloaded.isEmailVerified());
        assertEquals("new-hash", reloaded.getPasswordHash());

        // Refresh the class field to the post-update version before cleanUp() runs - it still holds
        // the pre-update, now-stale version and would otherwise itself risk an
        // OptimisticLockException on delete().
        user = reloaded;
    }

    @Test
    @Transactional
    void lockForUpdate_returnsTheRow_withoutMutatingIt() {
        user = userRepository.saveAndFlush(new User("lock-test@example.com", "original-hash"));

        User locked = userRepository.lockForUpdate(user.getId()).orElseThrow();

        assertEquals(user.getId(), locked.getId());
        assertEquals("original-hash", locked.getPasswordHash());
        assertTrue(!locked.isEmailVerified());
    }

    /**
     * Case V from the design spec's test matrix: proves @Version is real defense-in-depth, not
     * decorative, against exactly the class of bug this plan removes (a stale full-entity save
     * racing an atomic update). A managed User is loaded (version N), a concurrent atomic update
     * bumps the row to version N+1 in a SEPARATE, already-committed transaction, and the ORIGINAL
     * stale entity is then saved - Hibernate must reject it rather than silently overwrite the
     * newer state.
     */
    @Test
    @Transactional
    void save_onAStaleEntity_throwsOptimisticLockException_afterAConcurrentAtomicUpdate() {
        user = userRepository.saveAndFlush(new User("version-collision@example.com", "original-hash"));
        User staleReference = userRepository.findById(user.getId()).orElseThrow();

        int updated = userRepository.activateIfPending(user.getId(), "winner-hash");
        assertEquals(1, updated, "the atomic update must have committed and bumped the row's version");

        staleReference.changePassword("stale-write-hash");
        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                () -> userRepository.saveAndFlush(staleReference),
                "saving a stale entity after a concurrent atomic update must fail loudly, not silently overwrite");

        // Refresh the class field to the current (correctly-versioned) row before cleanUp() runs -
        // it still holds the pre-activation, now-stale version and would otherwise itself risk an
        // OptimisticLockException on delete().
        user = userRepository.findById(user.getId()).orElseThrow();
    }
}
