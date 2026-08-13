package cn.choosec.economy.model;

/** A saved location (world id + coords + rotation). Mirrors the legacy ServerRules HomeLocation. */
public record HomeLocation(String world, double x, double y, double z, float yaw, float pitch) {
}
