package com.dogpound.prideprism;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Loader;

/** Money for website orders/rewards goes through RealmCoin. Safe without it: RealmCoin classes load only in RealmCoinLive. */
final class RealmCoinBank {
    private RealmCoinBank() {}

    static boolean present() {
        return Loader.isModLoaded("realmcoin");
    }

    /** null = paid; otherwise why not (not enough coins, frozen...) */
    static String charge(MinecraftServer s, UUID u, String name, long cents, String what) {
        return RealmCoinLive.charge(s, u, name, cents, what);
    }

    static void pay(MinecraftServer s, UUID u, String name, long cents, String what) {
        RealmCoinLive.pay(s, u, name, cents, what);
    }

    static long balance(MinecraftServer s, UUID u, String name) {
        return RealmCoinLive.balance(s, u, name);
    }

    static void install() { RealmCoinLive.install(); }
    static void tables(java.sql.Statement st) throws Exception { RealmCoinLive.tables(st); }
    static void flush(java.sql.Connection c, MinecraftServer s, int tick) throws Exception { RealmCoinLive.flush(c, s, tick); }
}
