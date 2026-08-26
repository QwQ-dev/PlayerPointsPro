package org.black_ixx.playerpoints;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.black_ixx.playerpoints.manager.LocaleManager;
import org.black_ixx.playerpoints.models.Tuple;
import org.black_ixx.playerpoints.util.PointsUtils;
import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Vault economy layer for PlayerPoints.
 *
 * <p>Vault mutations are synchronous, so they wait for the database transaction to finish.
 */
public class PlayerPointsVaultLayer implements Economy {

    private final PlayerPoints plugin;
    private final LocaleManager localeManager;

    public PlayerPointsVaultLayer(PlayerPoints plugin) {
        this.plugin = plugin;
        this.localeManager = plugin.getManager(LocaleManager.class);
    }

    @Override
    public boolean isEnabled() {
        return this.plugin.isEnabled();
    }

    @Override
    public String getName() {
        return this.plugin.getName();
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 0;
    }

    @Override
    public String format(double amount) {
        return PointsUtils.formatPoints((int) amount) + " " + (amount == 1 ? this.currencyNameSingular() : this.currencyNamePlural());
    }

    @Override
    public String currencyNamePlural() {
        return this.localeManager.getLocaleMessage("currency-plural");
    }

    @Override
    public String currencyNameSingular() {
        return this.localeManager.getLocaleMessage("currency-singular");
    }

    @Override
    public boolean hasAccount(String playerName) {
        return true;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return true;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return this.hasAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        UUID uuid = this.handleTranslation(playerName);
        return uuid != null ? this.plugin.getAPI().look(uuid) : 0;
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return this.plugin.getAPI().look(player.getUniqueId());
    }

    @Override
    public double getBalance(String playerName, String worldName) {
        return this.getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String worldName) {
        return this.getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return this.getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return this.getBalance(player) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return this.has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return this.has(player, amount);
    }

    private static Integer toWholePoints(double amount) {
        if (!Double.isFinite(amount) || amount < 0 || amount > Integer.MAX_VALUE
                || amount != Math.rint(amount)) {
            return null;
        }
        return (int) amount;
    }

    private static EconomyResponse invalidAmount(double amount) {
        return new EconomyResponse(amount, 0, ResponseType.FAILURE,
                "PlayerPoints only supports non-negative whole amounts within the signed 32-bit range");
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return this.withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return this.withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        UUID uuid = this.handleTranslation(playerName);
        if (uuid == null)
            return new EconomyResponse(0, 0, ResponseType.FAILURE, "Invalid player");
        return this.withdraw(uuid, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return this.withdraw(player.getUniqueId(), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        UUID uuid = this.handleTranslation(playerName);
        if (uuid == null)
            return new EconomyResponse(0, 0, ResponseType.FAILURE, "Invalid player");
        return this.deposit(uuid, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return this.deposit(player.getUniqueId(), amount);
    }

    private EconomyResponse withdraw(UUID playerId, double amount) {
        Integer points = toWholePoints(amount);
        if (points == null)
            return invalidAmount(amount);
        try {
            int availablePoints = this.plugin.getAPI().look(playerId);
            if (points == 0)
                return new EconomyResponse(0, availablePoints, ResponseType.SUCCESS, null);
            boolean withdrawn = this.plugin.getAPI().take(playerId, points);
            if (!withdrawn) {
                String error = availablePoints < points
                        ? "Insufficient balance"
                        : "PlayerPoints rejected the withdrawal";
                return new EconomyResponse(amount, availablePoints, ResponseType.FAILURE, error);
            }

            int fallback = Math.max(0, availablePoints - points);
            int balance = this.lookAfterCommitOrFallback(playerId, fallback);
            return new EconomyResponse(amount, balance, ResponseType.SUCCESS, null);
        } catch (RuntimeException failure) {
            this.plugin.getLogger().log(Level.WARNING,
                    "Unable to withdraw PlayerPoints for " + playerId, failure);
            return new EconomyResponse(amount, 0, ResponseType.FAILURE,
                    "Unable to update the PlayerPoints balance");
        }
    }

    private EconomyResponse deposit(UUID playerId, double amount) {
        Integer points = toWholePoints(amount);
        if (points == null)
            return invalidAmount(amount);
        try {
            int previousBalance = this.plugin.getAPI().look(playerId);
            if (points == 0)
                return new EconomyResponse(0, previousBalance, ResponseType.SUCCESS, null);
            if (!this.plugin.getAPI().give(playerId, points)) {
                return new EconomyResponse(amount, previousBalance, ResponseType.FAILURE,
                        "PlayerPoints rejected the deposit");
            }

            long expected = (long) previousBalance + points;
            int fallback = expected <= Integer.MAX_VALUE ? (int) expected : previousBalance;
            int balance = this.lookAfterCommitOrFallback(playerId, fallback);
            return new EconomyResponse(amount, balance, ResponseType.SUCCESS, null);
        } catch (RuntimeException failure) {
            this.plugin.getLogger().log(Level.WARNING,
                    "Unable to deposit PlayerPoints for " + playerId, failure);
            return new EconomyResponse(amount, 0, ResponseType.FAILURE,
                    "Unable to update the PlayerPoints balance");
        }
    }

    private int lookAfterCommitOrFallback(UUID playerId, int fallback) {
        try {
            return this.plugin.getAPI().look(playerId);
        } catch (RuntimeException failure) {
            this.plugin.getLogger().log(Level.WARNING,
                    "Unable to read the committed PlayerPoints balance for " + playerId,
                    failure);
            return fallback;
        }
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return this.depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return this.depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "PlayerPoints does not support banks");
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "PlayerPoints does not support banks");
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "PlayerPoints does not support banks");
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "PlayerPoints does not support banks");
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "PlayerPoints does not support banks");
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "PlayerPoints does not support banks");
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "PlayerPoints does not support banks");
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "PlayerPoints does not support banks");
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "PlayerPoints does not support banks");
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "PlayerPoints does not support banks");
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "PlayerPoints does not support banks");
    }

    @Override
    public List<String> getBanks() {
        return Collections.emptyList();
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return this.createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String world) {
        return true;
    }

    private UUID handleTranslation(String name) {
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            Tuple<UUID, String> player = PointsUtils.getPlayerByName(name);
            return player == null ? null : player.getFirst();
        }
    }

}
