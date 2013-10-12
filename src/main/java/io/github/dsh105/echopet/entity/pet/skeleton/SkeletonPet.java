package io.github.dsh105.echopet.entity.pet.skeleton;

import io.github.dsh105.echopet.data.PetType;
import io.github.dsh105.echopet.entity.pet.Pet;
import org.bukkit.entity.Player;


public class SkeletonPet extends Pet {

    boolean wither;
    boolean equipment;

    public SkeletonPet(Player owner, PetType petType) {
        super(owner, petType);
        //this.equipment = EchoPet.getPluginInstance().options.shouldHaveEquipment(pet.getPet().getPetType());
    }

    public void setWither(boolean flag) {
        ((EntitySkeletonPet) getEntityPet()).setWither(flag);
        this.wither = flag;
    }

    public boolean isWither() {
        return this.wither;
    }

}