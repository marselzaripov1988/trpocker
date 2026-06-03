package com.truholdem.dto.wallet;

import java.util.List;

import com.truholdem.model.CryptoAsset;

/** Free/assigned address counts per asset, for monitoring pool depth (low-watermark refill). */
public record PoolStatusResponse(List<AssetCount> assets) {

    public record AssetCount(CryptoAsset asset, long free, long assigned) {
    }
}
