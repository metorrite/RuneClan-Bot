package com.runeclans.bot.hubcommunity;

import com.runeclans.bot.poll.PollRepository;
import com.runeclans.bot.poll.PollStore;
import com.runeclans.bot.signup.SignupRepository;
import com.runeclans.bot.signup.SignupStore;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.function.Supplier;

/**
 * The poll and signup stores {@code PollService} and {@code SignupService} use (ticket F3): one object each that sends every call to the
 * bot's own repository or to the hub's store, by the {@code hub-community} switch at the moment of the call ({@link HubCommunity#onHub}).
 * The services never touch a repository directly.
 */
@BConfiguration
public class CommunityStores {
    @BService
    public static HubPollStore hubPollStore(HubCommunityClient client, HubCommunityMaps maps) {
        return new HubPollStore(client, maps, Clock.systemUTC());
    }

    @BService
    public static HubSignupStore hubSignupStore(HubCommunityClient client, HubCommunityMaps maps) {
        return new HubSignupStore(client, maps, Clock.systemUTC());
    }

    @BService
    public static PollStore pollStore(PollRepository bot, HubPollStore hub, HubCommunity community) {
        return switching(PollStore.class, () -> community.onHub() ? hub : bot);
    }

    @BService
    public static SignupStore signupStore(SignupRepository bot, HubSignupStore hub, HubCommunity community) {
        return switching(SignupStore.class, () -> community.onHub() ? hub : bot);
    }

    /** An {@code type} that hands each call to whichever store {@code pick} names right then. */
    public static <T> T switching(Class<T> type, Supplier<? extends T> pick) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "switching " + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            try {
                return method.invoke(pick.get(), args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
        return type.cast(proxy);
    }
}
