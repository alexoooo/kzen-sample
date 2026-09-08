package tech.kzen.sample.plugin.analysis.dated;

import tech.kzen.sample.itch.model.OrderLifecycle;

public record DatedOrder(String date, String symbol, OrderLifecycle order) {}
