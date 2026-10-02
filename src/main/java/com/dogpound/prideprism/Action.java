package com.dogpound.prideprism;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** One thing that happened: who did what, where, when, and the block/item before and after. */
public final class Action {
    public long id, time;
    public int dim, x, y, z;
    public String action, who, uuid;
    public String blockOld, blockNew, item, extra;
    public int metaOld, metaNew, itemMeta, amount;
    public NBTTagCompound nbtOld, nbtNew;
    public boolean rolledBack;

    public static Action at(String action, World w, BlockPos p, String who, EntityPlayer player) {
        Action a = new Action();
        a.time = System.currentTimeMillis();
        a.action = action;
        a.dim = w.provider.getDimension();
        a.x = p.getX(); a.y = p.getY(); a.z = p.getZ();
        a.who = who;
        if (player != null) { a.who = player.getName(); a.uuid = player.getUniqueID().toString(); }
        return a;
    }

    @SuppressWarnings("deprecation")
    public Action before(IBlockState s, TileEntity te) {
        blockOld = s.getBlock().getRegistryName() + "";
        metaOld = s.getBlock().getMetaFromState(s);
        nbtOld = te == null ? null : te.writeToNBT(new NBTTagCompound());
        return this;
    }

    @SuppressWarnings("deprecation")
    public Action after(IBlockState s, TileEntity te) {
        blockNew = s.getBlock().getRegistryName() + "";
        metaNew = s.getBlock().getMetaFromState(s);
        nbtNew = te == null ? null : te.writeToNBT(new NBTTagCompound());
        return this;
    }

    public Action item(ItemStack st, int count) {
        item = st.getItem().getRegistryName() + "";
        itemMeta = st.getMetadata();
        amount = count;
        if (st.hasTagCompound()) nbtNew = st.getTagCompound().copy(); // enchantments, names, contents
        return this;
    }

    public Action extra(String s) {
        extra = s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
        return this;
    }

    void bind(PreparedStatement ps) throws Exception {
        ps.setLong(1, time); ps.setInt(2, dim); ps.setInt(3, x); ps.setInt(4, y); ps.setInt(5, z);
        ps.setString(6, action); ps.setString(7, who); ps.setString(8, uuid);
        ps.setString(9, blockOld); ps.setInt(10, metaOld); ps.setString(11, blockNew); ps.setInt(12, metaNew);
        ps.setBytes(13, pack(nbtOld)); ps.setBytes(14, pack(nbtNew));
        ps.setString(15, item); ps.setInt(16, itemMeta); ps.setInt(17, amount);
        if (extra == null) ps.setNull(18, Types.VARCHAR); else ps.setString(18, extra);
    }

    static Action read(ResultSet rs) throws Exception {
        Action a = new Action();
        a.id = rs.getLong("id"); a.time = rs.getLong("t"); a.dim = rs.getInt("dim");
        a.x = rs.getInt("x"); a.y = rs.getInt("y"); a.z = rs.getInt("z");
        a.action = rs.getString("action"); a.who = rs.getString("who"); a.uuid = rs.getString("uuid");
        a.blockOld = rs.getString("block_old"); a.metaOld = rs.getInt("meta_old");
        a.blockNew = rs.getString("block_new"); a.metaNew = rs.getInt("meta_new");
        a.nbtOld = unpack(rs.getBytes("nbt_old")); a.nbtNew = unpack(rs.getBytes("nbt_new"));
        a.item = rs.getString("item"); a.itemMeta = rs.getInt("item_meta"); a.amount = rs.getInt("amount");
        a.extra = rs.getString("extra"); a.rolledBack = rs.getInt("rolled_back") != 0;
        return a;
    }

    private static byte[] pack(NBTTagCompound t) throws Exception {
        if (t == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(t, out); // gzip: chest/machine contents stay small
        return out.toByteArray();
    }

    private static NBTTagCompound unpack(byte[] b) throws Exception {
        return b == null ? null : CompressedStreamTools.readCompressed(new ByteArrayInputStream(b));
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }
}
