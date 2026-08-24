package org.black_ixx.playerpoints.models;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class DetailedPointsBalance extends PointsBalance {

    private final List<TemporaryPointGrant> temporaryGrants;

    public DetailedPointsBalance(int permanent, Collection<TemporaryPointGrant> temporaryGrants) {
        super(permanent, sumTemporaryPoints(temporaryGrants));
        this.temporaryGrants = Collections.unmodifiableList(new ArrayList<>(temporaryGrants));
    }

    private static int sumTemporaryPoints(Collection<TemporaryPointGrant> temporaryGrants) {
        if (temporaryGrants == null)
            throw new NullPointerException("temporaryGrants");

        long total = 0;
        for (TemporaryPointGrant grant : temporaryGrants) {
            if (grant == null)
                throw new NullPointerException("temporaryGrants must not contain null values");
            total = Math.addExact(total, grant.getAmount());
        }
        if (total > Integer.MAX_VALUE)
            throw new IllegalArgumentException("temporary balance exceeds Integer.MAX_VALUE");
        return (int) total;
    }

    public List<TemporaryPointGrant> getTemporaryGrants() {
        return this.temporaryGrants;
    }

}
