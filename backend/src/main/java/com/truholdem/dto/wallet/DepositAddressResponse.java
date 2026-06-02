package com.truholdem.dto.wallet;

import com.truholdem.model.CryptoAsset;

public record DepositAddressResponse(String asset, String network, String address) {

    public static DepositAddressResponse of(CryptoAsset asset, String address) {
        return new DepositAddressResponse(asset.getSymbol(), asset.getNetwork(), address);
    }
}
