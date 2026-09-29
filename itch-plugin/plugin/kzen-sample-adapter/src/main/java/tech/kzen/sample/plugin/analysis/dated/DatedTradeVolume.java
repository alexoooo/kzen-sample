package tech.kzen.sample.plugin.analysis.dated;

public record DatedTradeVolume(String date, String symbol, long tradeEvents, long shares) {}
