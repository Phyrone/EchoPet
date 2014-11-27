/*
 * This file is part of EchoPet.
 *
 * EchoPet is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * EchoPet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with EchoPet.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.dsh105.echopet.api.entity.pet;

import com.captainbern.minecraft.conversion.BukkitUnwrapper;
import com.captainbern.minecraft.reflection.MinecraftReflection;
import com.captainbern.reflection.Reflection;
import com.captainbern.reflection.SafeField;
import com.dsh105.commodus.GeneralUtil;
import com.dsh105.commodus.IdentUtil;
import com.dsh105.commodus.StringUtil;
import com.dsh105.commodus.particle.Particle;
import com.dsh105.echopet.api.config.Lang;
import com.dsh105.echopet.api.config.PetSettings;
import com.dsh105.echopet.api.config.Settings;
import com.dsh105.echopet.api.entity.*;
import com.dsh105.echopet.api.entity.ai.PetGoalSelector;
import com.dsh105.echopet.api.entity.ai.SimplePetGoalSelector;
import com.dsh105.echopet.api.entity.ai.goal.PetGoalFloat;
import com.dsh105.echopet.api.entity.ai.goal.PetGoalFollowOwner;
import com.dsh105.echopet.api.entity.ai.goal.PetGoalLookAtPlayer;
import com.dsh105.echopet.api.entity.entitypet.EntityPet;
import com.dsh105.echopet.api.entity.entitypet.EntityPetModifier;
import com.dsh105.echopet.api.entity.pet.type.EnderDragonPet;
import com.dsh105.echopet.api.event.PetRideJumpEvent;
import com.dsh105.echopet.api.event.PetRideMoveEvent;
import com.dsh105.echopet.api.inventory.DataMenu;
import com.dsh105.echopet.api.plugin.EchoPet;
import com.dsh105.echopet.api.plugin.SQLPetManager;
import com.dsh105.echopet.util.Perm;
import org.apache.commons.lang.BooleanUtils;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class PetBase<T extends LivingEntity, S extends EntityPet> implements Pet<T, S> {

    private static Pattern PREVIOUS_NAME_PATTERN = Pattern.compile(".+\\s([0-9])\\b");

    protected SafeField<Boolean> JUMP_FIELD;

    private S entity;
    private UUID petId;

    private String ownerIdent;
    private String name;

    private PetGoalSelector petGoalSelector;

    private Pet rider;
    private boolean isRider;

    private boolean ownerInMountingProcess;
    private boolean owningRiding;
    private boolean hat;
    private boolean stationary;

    private boolean despawned;

    private double jumpHeight;
    private double rideSpeed;
    private boolean shouldVanish;

    protected PetBase(Player owner) {
        if (owner != null) {
            this.ownerIdent = IdentUtil.getIdentificationForAsString(owner);
            petId = UUID.randomUUID();
            while (EchoPet.getManager().getPetUniqueIdMap().containsKey(petId)) {
                petId = UUID.randomUUID();
            }

            this.entity = Spawn.spawn(this);
            if (this.entity != null) {
                // Begin initiating our EntityPet

                JUMP_FIELD = new Reflection().reflect(MinecraftReflection.getMinecraftClass("EntityLiving")).getSafeFieldByName(getModifier().getJumpField());

                entity.modifyBoundingBox(width(), height());
                entity.setFireProof(true);
                getBukkitEntity().setMaxHealth(getType().getMaxHealth());
                getBukkitEntity().setHealth(getBukkitEntity().getMaxHealth());
                jumpHeight = PetSettings.JUMP_HEIGHT.getValue(getType().storageName());
                rideSpeed = PetSettings.RIDE_SPEED.getValue(getType().storageName());

                this.petGoalSelector = new SimplePetGoalSelector(this);
                this.petGoalSelector.addGoal(new PetGoalFloat(), 0);
                this.petGoalSelector.addGoal(new PetGoalFollowOwner(), 1);
                this.petGoalSelector.addGoal(new PetGoalLookAtPlayer(), 2);

                getModifier().setAvoidsWater(false);
                getModifier().setAvoidSun(false);
                getModifier().setCanSwim(true);
                getModifier().setBreakDoors(false);
                getModifier().setEnterDoors(false);

                setName(getType().getDefaultName(getOwnerName()));
                teleportToOwner();

            }
        }
    }

    @Override
    public T getBukkitEntity() {
        return (T) getModifier().getBukkitEntity();
    }

    @Override
    public S getEntity() {
        return entity;
    }

    @Override
    public <P extends Pet<T, S>> EntityPetModifier<P> getModifier() {
        return (EntityPetModifier<P>) entity.getModifier();
    }

    @Override
    public UUID getPetId() {
        return petId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public PetGoalSelector getPetGoalSelector() {
        return petGoalSelector;
    }

    @Override
    public boolean setName(String name) {
        return setName(name, true);
    }

    @Override
    public boolean setName(String name, boolean sendFailMessage) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        if (name.length() > 32) {
            name = name.substring(0, 32);
        }

        if (EchoPet.getManager().getPetNameMapFor(ownerIdent).containsKey(name)) {
            Matcher matcher = PREVIOUS_NAME_PATTERN.matcher(name);
            if (matcher.matches()) {
                // Append a number onto the end to prevent duplicate names
                // This is especially problematic for multiple pets with the default name
                name.replace(matcher.group(0), " " + (GeneralUtil.toInteger(matcher.group(1)) + 1));
            } else {
                name += " 1";
            }
        }

        boolean allow = true;
        if (Settings.PET_NAME_REGEX_MATCHING.getValue()) {
            List<Map<String, String>> csRegex = Settings.PET_NAME_REGEX.getValue();
            if (!csRegex.isEmpty()) {
                for (Map<String, String> regexMap : csRegex) {
                    for (Map.Entry<String, String> entry : regexMap.entrySet()) {
                        if (name.matches(entry.getKey())) {
                            allow = !BooleanUtils.toBoolean(entry.getValue());
                        }
                    }
                }
            }
        }
        if (getOwner().hasPermission("echopet.pet.name.override") || allow || Settings.PET_NAME.getValue(name)) {
            name = ChatColor.translateAlternateColorCodes('&', name);
            if (Settings.STRIP_DIACRITICS.getValue()) {
                name = StringUtil.stripDiacritics(name);
            }
            EchoPet.getManager().unmapPetName(ownerIdent, this.name);

            this.name = name;
            getBukkitEntity().setCustomName(this.name);
            getBukkitEntity().setCustomNameVisible(PetSettings.TAG_VISIBLE.getValue(getType().storageName()));

            EchoPet.getManager().mapPetName(this);
            return true;
        }

        if (sendFailMessage) {
            Lang.NAME_NOT_ALLOWED.send(getOwner(), "name", name);
        }
        return false;
    }

    @Override
    public String getOwnerIdent() {
        return ownerIdent;
    }

    @Override
    public Player getOwner() {
        return IdentUtil.getPlayerOf(ownerIdent);
    }

    @Override
    public String getOwnerName() {
        return getOwner().getName();
    }

    @Override
    public PetType getType() {
        return entityInfo().type();
    }

    @Override
    public boolean isRider() {
        return isRider;
    }

    @Override
    public Pet getRider() {
        return rider;
    }

    @Override
    public float width() {
        return entityInfo().width();
    }

    @Override
    public float height() {
        return entityInfo().height();
    }

    @Override
    public void setDataValue(PetData petData, Object value) {
        AttributeManager.getModifier(this).setDataValue(this, petData, value);
    }

    @Override
    public void setDataValue(PetData... dataArray) {
        List<PetData> registeredData = getApplicableDataTypes();
        for (PetData petData : dataArray) {
            if (!registeredData.contains(petData)) {
                continue;
            }
            AttributeManager.getModifier(this).setDataValue(this, petData);
        }
    }

    @Override
    public void setDataValue(boolean on, PetData... dataArray) {
        List<PetData> registeredData = getApplicableDataTypes();
        for (PetData petData : dataArray) {
            if (!registeredData.contains(petData)) {
                continue;
            }
            if (petData.isType(PetData.Type.BOOLEAN)) {
                AttributeManager.getModifier(this).setDataValue(this, petData, on);
            } else {
                AttributeManager.getModifier(this).setDataValue(this, petData);
            }
        }
    }

    public Object getDataValue(PetData petData) {
        return AttributeManager.getModifier(this).getDataValue(this, petData);
    }

    public Object getDataValue(PetData.Type petDataType) {
        return AttributeManager.getModifier(this).getDataValue(this, petDataType);
    }

    @Override
    public void invertDataValue(PetData petData) {
        AttributeManager.getModifier(this).invertDataValue(this, petData);
    }

    @Override
    public List<PetData> getActiveDataValues() {
        return AttributeManager.getModifier(this).getActiveDataValues(this);
    }

    @Override
    public List<PetData> getApplicableDataTypes() {
        return AttributeManager.getModifier(this).getApplicableDataTypes();
    }

    @Override
    public boolean isStationary() {
        return stationary;
    }

    @Override
    public void setStationary(boolean flag) {
        this.stationary = flag;
    }

    @Override
    public void despawn(boolean makeDeathSound) {
        if (despawned) {
            // He's dead, Jim!
            return;
        }

        if (entity != null && getBukkitEntity() != null) {
            Particle.DEATH_CLOUD.builder().show(getLocation());
            getBukkitEntity().remove();
            if (makeDeathSound) {
                if (getDeathSound() != null && !getDeathSound().isEmpty()) {
                    entity.makeSound(getDeathSound(), 1.0F, 1.0F);
                }
            }
        }
        if (rider != null) {
            rider.despawn(false);
            rider = null;
        }

        this.despawned = true;
    }

    @Override
    public Pet spawnRider(PetType type, boolean sendFailMessage) {
        String failMessage;
        if (!PetSettings.ENABLE.getValue(type.storageName())) {
            failMessage = Lang.PET_TYPE_DISABLED.getValue("type", type.humanName());
        } else if (!PetSettings.ALLOW_RIDERS.getValue(getType().storageName())) {
            failMessage = Lang.RIDERS_DISABLED.getValue("type", getType().humanName());
        } else {
            if (owningRiding) {
                setOwnerRiding(false);
            }

            Pet newRider = EchoPet.getPetRegistry().spawn(type, getOwner());
            if (newRider != null) {
                if (rider != null) {
                    rider.despawn(false);
                }

                rider = newRider;
                ((PetBase) rider).isRider = true;
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (getBukkitEntity() != null) {
                            getBukkitEntity().setPassenger(getRider().getBukkitEntity());
                        }
                    }
                }.runTaskLater(EchoPet.getCore(), 5L);
            }
            return rider;
        }

        if (sendFailMessage) {
            getOwner().sendMessage(failMessage);
        }
        return null;
    }

    @Override
    public Pet spawnRider(Pet pet, boolean sendFailMessage) {
        if (pet == null) {
            return null;
        }
        String failMessage;
        if (!PetSettings.ENABLE.getValue(pet.getType().storageName())) {
            failMessage = Lang.PET_TYPE_DISABLED.getValue("type", pet.getType().humanName());
        } else if (!PetSettings.ALLOW_RIDERS.getValue(getType().storageName())) {
            failMessage = Lang.RIDERS_DISABLED.getValue("type", getType().humanName());
        } else {
            if (owningRiding) {
                setOwnerRiding(false);
            }

            if (rider != null) {
                rider.despawn(false);
            }

            rider = pet;
            ((PetBase) rider).isRider = true;
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (getBukkitEntity() != null) {
                        getBukkitEntity().setPassenger(getRider().getBukkitEntity());
                    }
                }
            }.runTaskLater(EchoPet.getCore(), 5L);
            return rider;
        }

        if (sendFailMessage) {
            getOwner().sendMessage(failMessage);
        }
        return null;
    }

    @Override
    public void despawnRider() {
        rider.despawn(true);
        if (EchoPet.getManager() instanceof SQLPetManager) {
            ((SQLPetManager) EchoPet.getManager()).clearRider(this);
        }
        rider = null;
    }

    @Override
    public boolean teleportToOwner() {
        return getOwner() != null && teleport(getOwner().getLocation());
    }

    @Override
    public boolean teleport(Location to) {
        if (entity == null || getModifier().isDead()) {
            return false;
        }

        if (rider != null) {
            rider.getBukkitEntity().eject();
            rider.getBukkitEntity().teleport(to);
        }
        boolean result = getBukkitEntity().teleport(to);
        if (rider != null) {
            getBukkitEntity().setPassenger(rider.getBukkitEntity());
        }
        return result;
    }

    @Override
    public Location getLocation() {
        return getBukkitEntity().getLocation();
    }

    @Override
    public double getJumpHeight() {
        return jumpHeight;
    }

    @Override
    public void setJumpHeight(double jumpHeight) {
        this.jumpHeight = jumpHeight;
    }

    @Override
    public double getRideSpeed() {
        return rideSpeed;
    }

    @Override
    public void setRideSpeed(double rideSpeed) {
        this.rideSpeed = rideSpeed;
    }

    @Override
    public boolean shouldVanish() {
        return shouldVanish;
    }

    @Override
    public void setShouldVanish(boolean flag) {
        this.shouldVanish = flag;
    }

    @Override
    public boolean isOwnerRiding() {
        return owningRiding;
    }

    @Override
    public boolean isOwnerInMountingProcess() {
        return ownerInMountingProcess;
    }

    @Override
    public void setOwnerRiding(boolean flag) {
        if (owningRiding == flag) {
            return;
        }
        if (hat) {
            setHat(false);
        }

        if (flag) {
            this.ownerInMountingProcess = true;

            if (rider != null) {
                rider.despawn(false);
            }
            getBukkitEntity().setPassenger(getOwner());
            if (this instanceof EnderDragonPet) {
                getModifier().setNoClipEnabled(false);
            }

            this.ownerInMountingProcess = false;

            entity.modifyBoundingBox(width() / 2, height() / 2);
        } else {
            if (this instanceof EnderDragonPet) {
                getModifier().setNoClipEnabled(true);
            }
            EchoPet.getManager().loadRider(this);
            entity.modifyBoundingBox(width(), height());
            teleportToOwner();
        }

        this.owningRiding = flag;
    }

    @Override
    public boolean isHat() {
        return hat;
    }

    @Override
    public void setHat(boolean flag) {
        if (hat == flag) {
            return;
        }
        if (owningRiding) {
            setOwnerRiding(false);
        }

        if (flag) {
            if (rider != null) {
                rider.despawn(false);
            }
            getOwner().setPassenger(getBukkitEntity());
        } else {
            getOwner().setPassenger(null);
            EchoPet.getManager().loadRider(this);
            entity.modifyBoundingBox(width(), height());
            teleportToOwner();
        }

        this.hat = flag;
    }

    private PetInfo entityInfo() {
        return this.getClass().getAnnotation(PetInfo.class);
    }

    @Override
    public void onError(Throwable e) {
        EchoPet.LOG.severe("Uh oh. Something bad happened");
        e.printStackTrace();
        // TODO: send the player a message
        EchoPet.getManager().removePet(this, false);
    }

    @Override
    public void onLive() {
        // This should NEVER happen. NEVER
        if (getModifier().getPet() == null) {
            despawn(false);
            return;
        }

        if (getOwner() == null || !getOwner().isOnline()) {
            EchoPet.getManager().removePet(this, false);
            return;
        }

        if (owningRiding && getModifier().getPassenger() == null && !ownerInMountingProcess) {
            setOwnerRiding(false);
        }



        for (Status status : new Status[]{Status.INVISIBLE, Status.SPRINTING, Status.SNEAKING}) {
            boolean entityStatus = getStatus(getBukkitEntity(), status);
            if (getStatus(getOwner(), status) != entityStatus) {
                if (status != Status.INVISIBLE || !shouldVanish()) {
                    setStatus(getBukkitEntity(), status, !entityStatus);
                }
            }
        }

        if (hat) {
            float yaw = (getType() == PetType.ENDER_DRAGON ? getOwner().getLocation().getYaw() - 180 : getOwner().getLocation().getYaw());
            getModifier().setYaw(yaw);
        }

        if (getOwner().isFlying() && PetSettings.CAN_FLY.getValue(getType().storageName())) {
            Vector direction = getOwner().getLocation().toVector().subtract(getLocation().toVector());
            getBukkitEntity().setVelocity(new Vector(direction.getX() + (getOwner().getLocation().getDirection().getX() > 0 ? 1 : -1), direction.getY(), (getOwner().getLocation().getDirection().getZ() > 0 ? 1 : -1)).normalize().multiply(0.3F));
        }
    }

    @Override
    public void onRide(float sideMotion, float forwardMotion) {
        if (getModifier().getPassenger() == null || getModifier().getPassenger() != getOwner()) {
            entity.updateMotion(sideMotion, forwardMotion);
            getModifier().setStepHeight(0.5F);
            return;
        }

        getModifier().setStepHeight(1.0F);
        getModifier().applyPitchAndYawChanges(getModifier().getPassenger().getLocation().getPitch() * 0.5F, getModifier().getPassenger().getLocation().getYaw());

        // Retrieve motion of passenger
        sideMotion = getModifier().getPassengerSideMotion() * 0.5F;
        forwardMotion = getModifier().getPassengerForwardMotion();

        if (forwardMotion <= 0F) {
            // Slow down backwards movement
            forwardMotion *= 0.25F;
        }
        // Sidewards motion is slower
        sideMotion *= 0.75F;

        PetRideMoveEvent moveEvent = new PetRideMoveEvent(this, forwardMotion, sideMotion);
        EchoPet.getCore().getServer().getPluginManager().callEvent(moveEvent);
        if (moveEvent.isCancelled()) {
            return;
        }

        getModifier().setSpeed((float) rideSpeed);
        // Apply all changes to entity motion
        entity.updateMotion(moveEvent.getSidewardMotionSpeed(), moveEvent.getForwardMotionSpeed());

        if (JUMP_FIELD != null) {
            boolean canFly = PetSettings.CAN_FLY.getValue(getType().storageName());
            double jumpHeight = canFly ? 0.5D : this.jumpHeight;
            if (canFly || getModifier().isGrounded()) {
                if (JUMP_FIELD.getAccessor().get(getModifier().getPassenger())) {
                    if (getOwner().isFlying()) {
                        getOwner().setFlying(false);
                    }
                    PetRideJumpEvent jumpEvent = new PetRideJumpEvent(this, jumpHeight);
                    EchoPet.getCore().getServer().getPluginManager().callEvent(jumpEvent);
                    if (!jumpEvent.isCancelled()) {
                        getModifier().setMotionY((float) jumpHeight);
                        if (!canFly) {
                            doJumpAnimation();
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean onInteract(Player player) {
        if (IdentUtil.areIdentical(player, getOwner())) {
            if (player.hasPermission(Perm.MENU)) {
                DataMenu.prepare(this).show(player);
            }
            return true;
        }
        return false;
    }

    @Override
    public void makeStepSound() {

    }

    @Override
    public void doJumpAnimation() {

    }

    @Override
    public String getHurtSound() {
        return "";
    }

    private static boolean getStatus(Entity entity, Status status) {
        Object handle = BukkitUnwrapper.getInstance().unwrap(entity);
        return (Boolean) new Reflection().reflect(MinecraftReflection.getMinecraftClass("Entity")).getSafeMethod(status.getGetter()).getAccessor().invoke(handle);
    }

    private static void setStatus(Entity entity, Status status, boolean value) {
        Object handle = BukkitUnwrapper.getInstance().unwrap(entity);
        new Reflection().reflect(MinecraftReflection.getMinecraftClass("Entity")).getSafeMethod(status.getSetter(), boolean.class).getAccessor().invoke(handle, value);
    }

    private enum Status {
        INVISIBLE,
        SPRINTING,
        SNEAKING;

        private String key;

        Status() {
            this.key = StringUtil.capitalise(this.name(), true);
        }

        public String getKey() {
            return key;
        }

        public String getSetter() {
            return "set" + key;
        }

        public String getGetter() {
            return "is" + key;
        }
    }
}