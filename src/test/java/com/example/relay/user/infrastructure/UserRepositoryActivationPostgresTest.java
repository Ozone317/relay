package com.example.relay.user.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.relay.support.SharedPostgresContainer;
import com.example.relay.user.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
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
     * racing an atomic update).
     *
     * <p>
     * This whole test method runs inside ONE transaction (the class-level/method-level
     * {@code @Transactional}), not across two separate, independently-committed transactions - the
     * atomic update below does not "commit" mid-test in the ordinary sense. What actually makes
     * {@code staleReference} go stale is {@code activateIfPending}'s
     * {@code @Modifying(clearAutomatically = true)}: it clears this transaction's whole persistence
     * context, detaching the already-loaded {@code staleReference}. The subsequent
     * {@code userRepository.saveAndFlush(staleReference)} then routes through
     * {@code EntityManager.merge()} - {@code SimpleJpaRepository.save()} treats any entity with a
     * non-null id as "not new" - and it is merge()'s own version comparison (the detached, still
     * version-N {@code staleReference} against the row's already version-(N+1) state after the
     * atomic update) that throws {@code ObjectOptimisticLockingFailureException}. The test's actual
     * proof - a stale entity's save is rejected after a concurrent version bump - still holds; only
     * the mechanism above is what produces it, not a separate committed transaction.
     */
    @Test
    @Transactional
    void save_onAStaleEntity_throwsOptimisticLockException_afterAConcurrentAtomicUpdate() {
        user = userRepository.saveAndFlush(new User("version-collision@example.com", "original-hash"));
        User staleReference = userRepository.findById(user.getId()).orElseThrow();

        int updated = userRepository.activateIfPending(user.getId(), "winner-hash");
        assertEquals(1, updated,
                "the atomic update must have taken effect and bumped the row's version (via clearAutomatically, "
                        + "detaching staleReference) before the stale save below is attempted");

        staleReference.changePassword("stale-write-hash");
        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                () -> userRepository.saveAndFlush(staleReference),
                "saving a stale (now-detached) entity after a concurrent atomic update must fail loudly via "
                        + "merge()'s version check, not silently overwrite");

        // Refresh the class field to the current (correctly-versioned) row before cleanUp() runs -
        // it still holds the pre-activation, now-stale version and would otherwise itself risk an
        // OptimisticLockException on delete().
        user = userRepository.findById(user.getId()).orElseThrow();
    }
}
