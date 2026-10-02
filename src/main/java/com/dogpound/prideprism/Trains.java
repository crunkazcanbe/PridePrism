package com.dogpound.prideprism;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Every nerdy train stat, for Immersive Railroading and Traincraft (read by reflection: both are optional).
 * Once a second: where every loaded car is → odometer, speed, grade, braking, fuel, freight ton-km,
 * passenger-km, consist length, boiler/engine heat. Moving stretches become trips. Drivers get their own totals.
 */
public final class Trains {
    private static final double MAX_STEP = 150; // m in one second (540 km/h): more than that = teleported, not driven

    /** running totals for one car since the last flush (+ what we need to keep between seconds) */
    static final class Car {
        String mod, def, name, owner;
        int dim; double x, y, z;
        boolean seen;
        // since last flush (added onto the DB row)
        double meters, fuel, tonKm, passengerKm;
        int moving, idle, trips, overheats;
        // record highs (DB keeps the max)
        double topKmh, maxGrade, maxDecel, maxTractive, maxBoiler, maxEngine;
        int maxConsist;
        // between seconds
        double lastKmh, lastFuelLeft = -1; boolean lastOverheat;
        // the trip in progress
        long tripStart; int tripIdle; double tripMeters, tripTop, tripFuel, tripX, tripZ; int tripPassengers;
    }

    static final class Driver { String name; double meters; int seconds; double topKmh; final Map<String, Double> byStock = new HashMap<>(); }

    static final Map<UUID, Car> cars = new ConcurrentHashMap<>();
    static final Map<UUID, Driver> drivers = new ConcurrentHashMap<>();
    private static final List<Object[]> finishedTrips = new ArrayList<>();
    private static int tick;

    // ---- reflection, looked up once ----
    private static Class<?> irStock, irMoveable, irCoupleable, irLoco, irSteam, irDiesel, irTank, umcModded, tcStock, tcLoco;
    private static boolean looked;

    private static void lookUp() {
        if (looked) return;
        looked = true;
        irStock = cls("cam72cam.immersiverailroading.entity.EntityRollingStock");
        irMoveable = cls("cam72cam.immersiverailroading.entity.EntityMoveableRollingStock");
        irCoupleable = cls("cam72cam.immersiverailroading.entity.EntityCoupleableRollingStock");
        irLoco = cls("cam72cam.immersiverailroading.entity.Locomotive");
        irSteam = cls("cam72cam.immersiverailroading.entity.LocomotiveSteam");
        irDiesel = cls("cam72cam.immersiverailroading.entity.LocomotiveDiesel");
        irTank = cls("cam72cam.immersiverailroading.entity.FreightTank");
        umcModded = cls("cam72cam.mod.entity.ModdedEntity");
        tcStock = cls("train.common.api.EntityRollingStock");
        tcLoco = cls("train.common.api.Locomotive");
    }

    private static Class<?> cls(String n) {
        try { return Class.forName(n); } catch (Throwable t) { return null; }
    }

    private static final Map<String, Method> methods = new ConcurrentHashMap<>();

    /** call a no-arg method by name (cached); null if it isn't there or throws */
    static Object call(Object o, String name) {
        if (o == null) return null;
        String key = o.getClass().getName() + "#" + name;
        Method m = methods.get(key);
        if (m == null) {
            try {
                m = o.getClass().getMethod(name);
                m.setAccessible(true);
            } catch (Throwable t) { return null; }
            methods.put(key, m);
        }
        try { return m.invoke(o); } catch (Throwable t) { return null; }
    }

    static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ++tick % 20 != 0 || !PridePrism.logTrains) return;
        lookUp();
        if (irStock == null && tcStock == null) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        for (Car c : cars.values()) c.seen = false;
        for (WorldServer w : server.worlds) {
            for (Entity ent : new ArrayList<>(w.loadedEntityList)) {
                try {
                    if (umcModded != null && umcModded.isInstance(ent) && irStock != null) {
                        Object self = call(ent, "getSelf");
                        if (irStock.isInstance(self)) ir(self, ent, w);
                    } else if (tcStock != null && tcStock.isInstance(ent)) {
                        tc(ent, w);
                    }
                } catch (Throwable ignored) { } // a strange car never stops the tracker
            }
        }
        for (Map.Entry<UUID, Car> en : cars.entrySet()) if (!en.getValue().seen) endTrip(en.getKey(), en.getValue(), true); // unloaded
    }

    private static Car car(UUID id, String mod, Entity ent) {
        Car c = cars.get(id);
        if (c == null) {
            c = new Car();
            c.mod = mod; c.x = ent.posX; c.y = ent.posY; c.z = ent.posZ; c.dim = ent.dimension; c.lastKmh = 0;
            cars.put(id, c);
        }
        c.seen = true;
        return c;
    }

    private void ir(Object stock, Entity ent, WorldServer w) {
        UUID id = ent.getUniqueID();
        Car c = car(id, "immersiverailroading", ent);
        Object def = call(stock, "getDefinition");
        c.def = (String) call(stock, "getDefinitionID");
        c.name = def != null ? String.valueOf(call(def, "name")) : c.def;
        double kmh = Math.abs(num(call(call(stock, "getCurrentSpeed"), "metric")));
        double weightKg = num(call(stock, "getWeight"));
        List<?> passengers = (List<?>) call(stock, "getPassengers");
        int pax = passengers == null ? 0 : passengers.size();
        int consist = 1;
        if (irCoupleable != null && irCoupleable.isInstance(stock)) { Object t = call(stock, "getTrain"); if (t instanceof List) consist = ((List<?>) t).size(); }
        double fuelLeft = -1;
        boolean overheat = false;
        if (irLoco != null && irLoco.isInstance(stock)) {
            c.maxTractive = Math.max(c.maxTractive, num(call(stock, "getCurrentTractiveEffort")));
            if (irSteam != null && irSteam.isInstance(stock)) {
                c.maxBoiler = Math.max(c.maxBoiler, num(call(stock, "getBoilerTemperature")));
                Object burn = call(stock, "getBurnTime");
                if (burn instanceof Map) { fuelLeft = 0; for (Object v : ((Map<?, ?>) burn).values()) fuelLeft += num(v); } // ticks of coal left
            }
            if (irDiesel != null && irDiesel.isInstance(stock)) {
                c.maxEngine = Math.max(c.maxEngine, num(call(stock, "getEngineTemperature")));
                overheat = Boolean.TRUE.equals(call(stock, "isEngineOverheated"));
                fuelLeft = num(call(stock, "getLiquidAmount"));                                                   // mB of diesel
            }
        }
        List<EntityPlayer> riders = new ArrayList<>();
        for (Entity p : ent.getPassengers()) if (p instanceof EntityPlayer) riders.add((EntityPlayer) p);
        if (passengers != null) for (Object p : passengers) { Object inner = call(p, "getInternal"); if (inner instanceof EntityPlayer && !riders.contains(inner)) riders.add((EntityPlayer) inner); }
        step(id, c, ent, kmh, weightKg, Math.max(pax, riders.size()), consist, fuelLeft, overheat,
            irLoco != null && irLoco.isInstance(stock) ? riders : null);
    }

    private void tc(Entity ent, WorldServer w) {
        UUID id = ent.getUniqueID();
        Car c = car(id, "traincraft", ent);
        c.def = String.valueOf(call(ent, "getTrainType"));
        c.name = String.valueOf(call(ent, "getTrainName"));
        c.owner = (String) call(ent, "getTrainOwner");
        double kmh = Math.sqrt(ent.motionX * ent.motionX + ent.motionZ * ent.motionZ) * 20 * 3.6; // blocks/tick -> km/h
        boolean loco = tcLoco != null && tcLoco.isInstance(ent);
        List<EntityPlayer> riders = new ArrayList<>();
        for (Entity p : ent.getPassengers()) if (p instanceof EntityPlayer) riders.add((EntityPlayer) p);
        step(id, c, ent, kmh, 0, riders.size(), 1, -1, false, loco ? riders : null);
    }

    /** one second of a car's life */
    private static void step(UUID id, Car c, Entity ent, double kmh, double weightKg, int pax, int consist, double fuelLeft, boolean overheat, List<EntityPlayer> drivers) {
        double dx = ent.posX - c.x, dz = ent.posZ - c.z, dy = ent.posY - c.y;
        double flat = Math.sqrt(dx * dx + dz * dz);
        double moved = Math.sqrt(flat * flat + dy * dy);
        if (ent.dimension != c.dim || moved > MAX_STEP) moved = 0; // teleported
        c.x = ent.posX; c.y = ent.posY; c.z = ent.posZ; c.dim = ent.dimension;
        if (kmh <= 0.01 && moved > 0.05) kmh = moved * 3.6; // no speed reading: distance per second is speed
        boolean moving = moved > 0.3 || kmh > 1;
        if (moving) { c.moving++; c.meters += moved; } else c.idle++;
        c.topKmh = Math.max(c.topKmh, kmh);
        if (flat > 2) c.maxGrade = Math.max(c.maxGrade, Math.abs(dy) / flat * 100);
        c.maxDecel = Math.max(c.maxDecel, (c.lastKmh - kmh) / 3.6);         // m/s² slowed in one second
        c.lastKmh = kmh;
        double km = moved / 1000;
        c.tonKm += weightKg / 1000 * km;
        c.passengerKm += pax * km;
        c.maxConsist = Math.max(c.maxConsist, consist);
        if (fuelLeft >= 0) {
            if (c.lastFuelLeft >= 0 && fuelLeft < c.lastFuelLeft) { c.fuel += c.lastFuelLeft - fuelLeft; c.tripFuel += c.lastFuelLeft - fuelLeft; }
            c.lastFuelLeft = fuelLeft; // refuelling (going up) isn't burning
        }
        if (overheat && !c.lastOverheat) c.overheats++;
        c.lastOverheat = overheat;
        if (drivers != null) for (EntityPlayer p : drivers) {
            Driver d = Trains.drivers.computeIfAbsent(p.getUniqueID(), k -> new Driver());
            d.name = p.getName(); d.meters += moved; d.seconds++; d.topKmh = Math.max(d.topKmh, kmh);
            d.byStock.merge(c.name == null ? "?" : c.name, moved, Double::sum);
        }
        // trips: a moving stretch between stops of 20 s or more
        if (moving) {
            if (c.tripStart == 0) { c.tripStart = System.currentTimeMillis(); c.tripX = c.x; c.tripZ = c.z; c.tripMeters = 0; c.tripTop = 0; c.tripFuel = 0; c.tripPassengers = 0; }
            c.tripMeters += moved; c.tripTop = Math.max(c.tripTop, kmh); c.tripPassengers = Math.max(c.tripPassengers, pax); c.tripIdle = 0;
        } else if (c.tripStart != 0 && ++c.tripIdle >= 20) endTrip(id, c, false);
    }

    private static void endTrip(UUID id, Car c, boolean unloaded) {
        if (c.tripStart == 0) return;
        long now = System.currentTimeMillis();
        if (c.tripMeters >= 100) {
            c.trips++;
            double hours = Math.max(1, now - c.tripStart - (unloaded ? 0 : 20_000L)) / 3_600_000.0;
            synchronized (finishedTrips) {
                finishedTrips.add(new Object[]{id.toString(), c.name, c.tripStart, now, c.dim, (int) c.tripX, (int) c.tripZ, (int) c.x, (int) c.z,
                    c.tripMeters, c.tripTop, c.tripMeters / 1000 / hours, c.tripFuel, c.tripPassengers});
            }
            Action a = new Action();
            a.time = now; a.action = "train-trip"; a.who = c.name == null ? "train" : c.name;
            a.dim = c.dim; a.x = (int) c.x; a.y = (int) c.y; a.z = (int) c.z;
            a.extra(String.format("%.2f km, top %.0f km/h, from %d,%d", c.tripMeters / 1000, c.tripTop, (int) c.tripX, (int) c.tripZ));
            Db.log(a);
        }
        c.tripStart = 0;
        if (unloaded) cars.remove(id);
    }

    static void tables(java.sql.Statement s) throws Exception {
        s.execute("CREATE TABLE IF NOT EXISTS pp_trains (stock_uuid CHAR(36) PRIMARY KEY, modid VARCHAR(32), def_id VARCHAR(160), name VARCHAR(160),"
            + "owner VARCHAR(64), first_seen BIGINT, last_seen BIGINT, dim INT, x INT, y INT, z INT, odometer_m DOUBLE NOT NULL DEFAULT 0,"
            + "moving_s BIGINT NOT NULL DEFAULT 0, idle_s BIGINT NOT NULL DEFAULT 0, trips INT NOT NULL DEFAULT 0, fuel_used DOUBLE NOT NULL DEFAULT 0,"
            + "ton_km DOUBLE NOT NULL DEFAULT 0, passenger_km DOUBLE NOT NULL DEFAULT 0, top_kmh DOUBLE NOT NULL DEFAULT 0, max_consist INT NOT NULL DEFAULT 0,"
            + "max_grade_pct DOUBLE NOT NULL DEFAULT 0, max_decel DOUBLE NOT NULL DEFAULT 0, max_tractive_n DOUBLE NOT NULL DEFAULT 0,"
            + "max_boiler_c DOUBLE NOT NULL DEFAULT 0, max_engine_c DOUBLE NOT NULL DEFAULT 0, overheats INT NOT NULL DEFAULT 0,"
            + "INDEX odo (odometer_m)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        s.execute("CREATE TABLE IF NOT EXISTS pp_train_drivers (uuid CHAR(36) PRIMARY KEY, name VARCHAR(64), driven_m DOUBLE NOT NULL DEFAULT 0,"
            + "driving_s BIGINT NOT NULL DEFAULT 0, top_kmh DOUBLE NOT NULL DEFAULT 0, favorite VARCHAR(160)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        s.execute("CREATE TABLE IF NOT EXISTS pp_train_trips (id BIGINT AUTO_INCREMENT PRIMARY KEY, stock_uuid CHAR(36), name VARCHAR(160),"
            + "start_t BIGINT, end_t BIGINT, dim INT, x1 INT, z1 INT, x2 INT, z2 INT, dist_m DOUBLE, top_kmh DOUBLE, avg_kmh DOUBLE,"
            + "fuel_used DOUBLE, passengers_max INT, INDEX st (stock_uuid), INDEX t (end_t)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /** every 30 s (Web thread): add this round's numbers onto each train's and driver's row, and save finished trips */
    static void flush(Connection c) throws Exception {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO pp_trains (stock_uuid,modid,def_id,name,owner,first_seen,last_seen,dim,x,y,z,"
                + "odometer_m,moving_s,idle_s,trips,fuel_used,ton_km,passenger_km,top_kmh,max_consist,max_grade_pct,max_decel,max_tractive_n,max_boiler_c,max_engine_c,overheats)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE modid=VALUES(modid), def_id=VALUES(def_id), name=VALUES(name),"
                + " owner=COALESCE(VALUES(owner),owner), last_seen=VALUES(last_seen), dim=VALUES(dim), x=VALUES(x), y=VALUES(y), z=VALUES(z),"
                + " odometer_m=odometer_m+VALUES(odometer_m), moving_s=moving_s+VALUES(moving_s), idle_s=idle_s+VALUES(idle_s), trips=trips+VALUES(trips),"
                + " fuel_used=fuel_used+VALUES(fuel_used), ton_km=ton_km+VALUES(ton_km), passenger_km=passenger_km+VALUES(passenger_km),"
                + " top_kmh=GREATEST(top_kmh,VALUES(top_kmh)), max_consist=GREATEST(max_consist,VALUES(max_consist)), max_grade_pct=GREATEST(max_grade_pct,VALUES(max_grade_pct)),"
                + " max_decel=GREATEST(max_decel,VALUES(max_decel)), max_tractive_n=GREATEST(max_tractive_n,VALUES(max_tractive_n)),"
                + " max_boiler_c=GREATEST(max_boiler_c,VALUES(max_boiler_c)), max_engine_c=GREATEST(max_engine_c,VALUES(max_engine_c)), overheats=overheats+VALUES(overheats)")) {
            for (Map.Entry<UUID, Car> e : cars.entrySet()) {
                Car k = e.getValue();
                if (k.moving == 0 && k.idle == 0) continue;
                int i = 1;
                ps.setString(i++, e.getKey().toString()); ps.setString(i++, k.mod); ps.setString(i++, k.def); ps.setString(i++, k.name); ps.setString(i++, k.owner);
                ps.setLong(i++, now); ps.setLong(i++, now); ps.setInt(i++, k.dim); ps.setInt(i++, (int) k.x); ps.setInt(i++, (int) k.y); ps.setInt(i++, (int) k.z);
                ps.setDouble(i++, k.meters); ps.setInt(i++, k.moving); ps.setInt(i++, k.idle); ps.setInt(i++, k.trips); ps.setDouble(i++, k.fuel);
                ps.setDouble(i++, k.tonKm); ps.setDouble(i++, k.passengerKm); ps.setDouble(i++, k.topKmh); ps.setInt(i++, k.maxConsist);
                ps.setDouble(i++, k.maxGrade); ps.setDouble(i++, k.maxDecel); ps.setDouble(i++, k.maxTractive); ps.setDouble(i++, k.maxBoiler);
                ps.setDouble(i++, k.maxEngine); ps.setInt(i, k.overheats);
                ps.addBatch();
                k.meters = k.fuel = k.tonKm = k.passengerKm = 0; k.moving = k.idle = k.trips = k.overheats = 0; // records stay; DB keeps the max
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO pp_train_drivers (uuid,name,driven_m,driving_s,top_kmh,favorite) VALUES (?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE name=VALUES(name), driven_m=driven_m+VALUES(driven_m), driving_s=driving_s+VALUES(driving_s),"
                + " top_kmh=GREATEST(top_kmh,VALUES(top_kmh)), favorite=COALESCE(VALUES(favorite),favorite)")) {
            for (Map.Entry<UUID, Driver> e : drivers.entrySet()) {
                Driver d = e.getValue();
                String fav = d.byStock.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
                ps.setString(1, e.getKey().toString()); ps.setString(2, d.name); ps.setDouble(3, d.meters); ps.setInt(4, d.seconds);
                ps.setDouble(5, d.topKmh); ps.setString(6, fav);
                ps.addBatch();
            }
            ps.executeBatch();
            drivers.clear();
        }
        List<Object[]> trips;
        synchronized (finishedTrips) { trips = new ArrayList<>(finishedTrips); finishedTrips.clear(); }
        if (trips.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO pp_train_trips (stock_uuid,name,start_t,end_t,dim,x1,z1,x2,z2,dist_m,top_kmh,avg_kmh,fuel_used,passengers_max)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (Object[] t : trips) { for (int i = 0; i < t.length; i++) ps.setObject(i + 1, t[i]); ps.addBatch(); }
            ps.executeBatch();
        }
    }
}
