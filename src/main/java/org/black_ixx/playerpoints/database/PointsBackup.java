package org.black_ixx.playerpoints.database;

import org.black_ixx.playerpoints.models.TemporaryPointGrant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PointsBackup {

    private final Map<UUID, Integer> permanentPoints;
    private final Map<UUID, String> usernames;
    private final List<TemporaryPointGrant> temporaryGrants;

    PointsBackup(Map<UUID, Integer> permanentPoints, Map<UUID, String> usernames,
                 List<TemporaryPointGrant> temporaryGrants) {
        this.permanentPoints = Collections.unmodifiableMap(new LinkedHashMap<>(permanentPoints));
        this.usernames = Collections.unmodifiableMap(new LinkedHashMap<>(usernames));
        this.temporaryGrants = Collections.unmodifiableList(new ArrayList<>(temporaryGrants));
    }

    public Map<UUID, Integer> getPermanentPoints() {
        return this.permanentPoints;
    }

    public Map<UUID, String> getUsernames() {
        return this.usernames;
    }

    public List<TemporaryPointGrant> getTemporaryGrants() {
        return this.temporaryGrants;
    }

}
