package tech.kzen.sample.embed.host;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tech.kzen.auto.server.context.KzenAutoHost;
import tech.kzen.sample.embed.host.catalog.ItchCatalog;
import tech.kzen.sample.embed.config.KzenHostProperties;

import java.nio.file.Path;


/**
 * The host's own domain objects as Spring beans, and the same objects offered to kzen through
 * {@link KzenAutoHost} under their declared Java interfaces (type-keyed, {@code isInstance} assignable): what
 * the host's controllers use and what a workspace's {@code @Service} constructor parameters receive is one
 * instance. The budget is created once and shared by both.
 */
@Configuration
public class HostServicesConfig {
    @Bean
    public WeightedBudget weightedBudget(KzenHostProperties properties) {
        return new WeightedBudget(properties.host().budgetBytesOrDefault());
    }


    @Bean(destroyMethod = "close")
    public HostDay hostDay(KzenHostProperties properties, WeightedBudget budget) {
        KzenHostProperties.Host host = properties.host();
        Path dataRoot = host.dataRoot() == null ? Path.of(System.getProperty("user.home"), "kzen-data", "itch") : host.dataRoot().toAbsolutePath().normalize();
        Path dayFile = host.dayFile() == null ? null : host.dayFile().toAbsolutePath().normalize();
        return new HostDay(dayFile, dataRoot, budget);
    }


    @Bean(destroyMethod = "close")
    public ItchCatalog itchCatalog(KzenHostProperties properties, WeightedBudget budget) {
        Path root = properties.host().dataRoot() == null
                ? Path.of(System.getProperty("user.home"), "kzen-data", "itch") : properties.host().dataRoot();
        return new ItchCatalog(root, properties.host().dayFile(), budget);
    }

    @Bean
    public TradeRepository tradeRepository(HostDay day) {
        return new FileTradeRepository(day);
    }


    @Bean
    public OrderBookService orderBookService(HostDay day) {
        return new GovernedOrderBookService(day);
    }


    @Bean
    public SymbolDayLoader symbolDayLoader(HostDay day) {
        return new GovernedSymbolDayLoader(day);
    }


    /** Registered by interface; a second registration of a type, or a type kzen itself provides, fails by name. */
    @Bean
    public KzenAutoHost kzenAutoHost(TradeRepository trades, OrderBookService books, SymbolDayLoader loader, ItchCatalog catalog) {
        return KzenAutoHost.Companion.builder()
                .service(TradeRepository.class, trades)
                .service(OrderBookService.class, books)
                .service(SymbolDayLoader.class, loader)
                .service(ItchCatalog.class, catalog)
                .build();
    }
}
