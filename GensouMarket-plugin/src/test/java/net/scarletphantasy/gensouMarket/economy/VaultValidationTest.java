package net.scarletphantasy.gensouMarket.economy;

import net.milkbowl.vault.economy.Economy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VaultValidationTest {
    @Test void invalidNumbersNeverReachEconomyProvider() throws Exception {
        var vault = new VaultHook();
        var economy = mock(Economy.class);
        var field = VaultHook.class.getDeclaredField("economy");
        field.setAccessible(true);
        field.set(vault, economy);
        for (double amount : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1}) {
            assertFalse(vault.has(null, amount));
            assertFalse(vault.withdraw(null, amount));
            assertFalse(vault.deposit(null, amount));
        }
        verifyNoInteractions(economy);
    }
}
