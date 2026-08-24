package org.black_ixx.playerpoints.manager;

import org.black_ixx.playerpoints.models.TemporaryPointGrant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class RankingSnapshotBuilder {

    private final Map<UUID, Account> accounts = new LinkedHashMap<>();

    void addRow(UUID playerId, String username, int permanentPoints, TemporaryPointGrant temporaryGrant) {
        Account account = this.accounts.computeIfAbsent(playerId,
                ignored -> new Account(playerId, username, permanentPoints));
        if (temporaryGrant != null)
            account.temporaryGrants.add(temporaryGrant);
    }

    List<Account> build() {
        return Collections.unmodifiableList(new ArrayList<>(this.accounts.values()));
    }

    static final class Account {

        private final UUID playerId;
        private final String username;
        private final int permanentPoints;
        private final List<TemporaryPointGrant> temporaryGrants;

        private Account(UUID playerId, String username, int permanentPoints) {
            this.playerId = playerId;
            this.username = username;
            this.permanentPoints = permanentPoints;
            this.temporaryGrants = new ArrayList<>();
        }

        UUID getPlayerId() {
            return this.playerId;
        }

        String getUsername() {
            return this.username;
        }

        int getPermanentPoints() {
            return this.permanentPoints;
        }

        List<TemporaryPointGrant> getTemporaryGrants() {
            return Collections.unmodifiableList(this.temporaryGrants);
        }

    }

}
