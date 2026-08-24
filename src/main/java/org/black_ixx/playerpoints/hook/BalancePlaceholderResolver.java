package org.black_ixx.playerpoints.hook;

import org.black_ixx.playerpoints.models.PointsBalance;
import org.black_ixx.playerpoints.util.PointsUtils;

import java.util.Locale;
import java.util.function.Supplier;

final class BalancePlaceholderResolver {

    private BalancePlaceholderResolver() {

    }

    static String resolve(String placeholder, PointsBalance balance) {
        return resolve(placeholder, () -> balance);
    }

    static String resolve(String placeholder,
                          Supplier<? extends PointsBalance> balanceSupplier) {
        String normalized = placeholder.toLowerCase(Locale.ROOT);
        PointsBalance balance;
        switch (normalized) {
            case "points":
                balance = balanceSupplier.get();
                return String.valueOf(balance.getTotal());
            case "points_formatted":
                balance = balanceSupplier.get();
                return PointsUtils.formatPoints(balance.getTotal());
            case "points_shorthand":
                balance = balanceSupplier.get();
                return PointsUtils.formatPointsShorthand(balance.getTotal());
            case "permanent":
            case "points_permanent":
                balance = balanceSupplier.get();
                return String.valueOf(balance.getPermanent());
            case "permanent_formatted":
            case "points_permanent_formatted":
                balance = balanceSupplier.get();
                return PointsUtils.formatPoints(balance.getPermanent());
            case "permanent_shorthand":
            case "points_permanent_shorthand":
                balance = balanceSupplier.get();
                return PointsUtils.formatPointsShorthand(balance.getPermanent());
            case "temporary":
            case "points_temporary":
                balance = balanceSupplier.get();
                return String.valueOf(balance.getTemporary());
            case "temporary_formatted":
            case "points_temporary_formatted":
                balance = balanceSupplier.get();
                return PointsUtils.formatPoints(balance.getTemporary());
            case "temporary_shorthand":
            case "points_temporary_shorthand":
                balance = balanceSupplier.get();
                return PointsUtils.formatPointsShorthand(balance.getTemporary());
            default:
                return null;
        }
    }

}
