package com.truholdem.dto.wallet;

import java.math.BigDecimal;

import com.truholdem.model.WalletAccount;

public record WalletBalanceResponse(String asset, String network, BigDecimal balance) {

    public static WalletBalanceResponse from(WalletAccount a) {
        return new WalletBalanceResponse(a.getAsset().getSymbol(), a.getAsset().getNetwork(), a.getBalance());
    }
}
