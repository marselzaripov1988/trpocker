package com.truholdem.model;

/**
 * Supported crypto assets for wallet deposits/withdrawals. The network is baked into the value because it
 * is the user-visible choice that matters (e.g. USDT on TRC-20 vs ERC-20). {@code decimals} is the asset's
 * on-chain precision, kept for future minor-unit conversions; balances are stored as {@link java.math.BigDecimal}.
 */
public enum CryptoAsset {

    USDT_TRC20("USDT", "TRC20", 6),
    USDT_ERC20("USDT", "ERC20", 6),
    USDT_SOL("USDT", "SPL", 6),
    BTC("BTC", "BTC", 8),
    ETH("ETH", "ETH", 18),
    LTC("LTC", "LTC", 8);

    private final String symbol;
    private final String network;
    private final int decimals;

    CryptoAsset(String symbol, String network, int decimals) {
        this.symbol = symbol;
        this.network = network;
        this.decimals = decimals;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getNetwork() {
        return network;
    }

    public int getDecimals() {
        return decimals;
    }
}
