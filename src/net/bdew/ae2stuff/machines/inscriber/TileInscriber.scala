/*
 * Copyright (c) bdew, 2014 - 2015
 * https://github.com/bdew/ae2stuff
 *
 * This mod is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://bdew.net/minecraft-mod-public-license/
 */

package net.bdew.ae2stuff.machines.inscriber

import appeng.api.AEApi
import appeng.api.config.Upgrades
import appeng.api.features.{IInscriberRecipe, InscriberProcessType}
import appeng.api.networking.GridNotification
import net.bdew.ae2stuff.grid.{GridTile, PoweredTile}
import net.bdew.ae2stuff.misc.UpgradeInventory
import net.bdew.lib.PimpVanilla._
import net.bdew.lib.block.TileKeepData
import net.bdew.lib.data.base.{TileDataSlots, UpdateKind}
import net.bdew.lib.data.{DataSlotBoolean, DataSlotFloat, DataSlotOption}
import net.bdew.lib.items.ItemUtils
import net.bdew.lib.tile.inventory.{PersistentInventoryTile, SidedInventory}
import net.minecraft.block.state.IBlockState
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.util.EnumFacing
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

class TileInscriber extends TileDataSlots with GridTile with SidedInventory with PersistentInventoryTile with PoweredTile with TileKeepData {
  override def getSizeInventory = 4
  override def getMachineRepresentation = new ItemStack(BlockInscriber)
  override def powerCapacity = MachineInscriber.powerCapacity

  object slots {
    val top = 0
    val middle = 1
    val bottom = 2
    val output = 3
  }

  val upgrades = new UpgradeInventory("upgrades", this, 5, Set(Upgrades.SPEED))
  val progress = DataSlotFloat("progress", this).setUpdate(UpdateKind.SAVE, UpdateKind.GUI)
  val output = DataSlotOption[ItemStack]("output", this).setUpdate(UpdateKind.SAVE)

  val topLocked = DataSlotBoolean("topLocked", this, true).setUpdate(UpdateKind.SAVE, UpdateKind.GUI)
  val bottomLocked = DataSlotBoolean("bottomLocked", this, true).setUpdate(UpdateKind.SAVE, UpdateKind.GUI)

  persistLoad.listen(tag => {
    // This forces the values to be true when loading from older versions
    if (!tag.hasKey("topLocked")) topLocked := true
    if (!tag.hasKey("bottomLocked")) bottomLocked := true
  })

  def isWorking = output.isDefined

  serverTick.listen(() => {
    if (isAwake) {
      if (!isWorking) {
        // No progress going - try starting
        findFinalRecipe foreach { recipe =>
          output.set(recipe.getOutput.copy())
          progress := 0
          decrStackSize(slots.middle, 1)
          if (recipe.getProcessType == InscriberProcessType.PRESS) {
            decrStackSize(slots.top, 1)
            decrStackSize(slots.bottom, 1)
          }
        }
        if (!isWorking) // Failed - go sleep
          sleep()
      }
      if (isWorking) {
        // Have something to do
        if (progress < 1) {
          // Not finished - try progressing
          val progressPerTick = (1F / MachineInscriber.cycleTicks) * (1 + upgrades.cards(Upgrades.SPEED))
          val powerNeeded = MachineInscriber.cyclePower * progressPerTick
          if (powerStored >= powerNeeded) {
            // Have enough power - consume it and add to progress
            progress += progressPerTick
            powerStored -= powerNeeded
          } else {
            // Not enough power - sleep
            sleep()
          }
        }
        if (progress >= 1) {
          // Finished - try to output
          output foreach { toOutput =>
            val oStack = getStackInSlot(slots.output)
            if (oStack == null || (ItemUtils.isSameItem(oStack, toOutput) && oStack.stackSize + toOutput.stackSize <= oStack.getMaxStackSize)) {
              // Can output - finish process
              if (oStack == null) {
                setInventorySlotContents(slots.output, toOutput)
              } else {
                oStack.stackSize += toOutput.stackSize
                markDirty()
              }
              output.unset()
              progress := 0
            } else {
              // Can't output - switch to sleep mode
              sleep()
            }
          }
        }
        requestPowerIfNeeded()
      }
    }
  })

  override def afterTileBreakSave(t: NBTTagCompound): NBTTagCompound = {
    t.removeTag("ae_node")
    t
  }

  override def onGridNotification(p1: GridNotification): Unit = {
    wakeup()
  }

  override def markDirty(): Unit = {
    wakeup()
    super.markDirty()
  }

  allowSided = true

  import scala.collection.JavaConversions._

  def findFinalRecipe: Option[IInscriberRecipe] =
    AEApi.instance().registries().inscriber().getRecipes find isMatchingFullRecipe

  def isMatchingFullRecipe(rec: IInscriberRecipe) = getStackInSlot(slots.middle) != null &&
    ItemUtils.isSameItem(rec.getTopOptional.orElse(null), getStackInSlot(slots.top)) &&
    ItemUtils.isSameItem(rec.getBottomOptional.orElse(null), getStackInSlot(slots.bottom)) &&
    rec.getInputs.exists(rs => ItemUtils.isSameItem(rs, getStackInSlot(slots.middle)))

  def isMatchingPartialRecipe(rec: IInscriberRecipe, top: Option[ItemStack], middle: Option[ItemStack], bottom: Option[ItemStack]): Boolean = {
    if (top.isDefined) {
      if (!rec.getTopOptional.isPresent) return false
      if (!ItemUtils.isSameItem(rec.getTopOptional.get(), top.get)) return false
    }
    if (middle.isDefined) {
      if (!rec.getInputs.exists(rs => ItemUtils.isSameItem(rs, middle.get))) return false
    }
    if (bottom.isDefined) {
      if (!rec.getBottomOptional.isPresent) return false
      if (!ItemUtils.isSameItem(rec.getBottomOptional.get(), bottom.get)) return false
    }
    true
  }

  def isValidPartialRecipe(top: Option[ItemStack], middle: Option[ItemStack], bottom: Option[ItemStack]): Boolean = {
    AEApi.instance().registries().inscriber().getRecipes.exists(rec => isMatchingPartialRecipe(rec, top, middle, bottom))
  }

  override def isItemValidForSlot(slot: Int, stack: ItemStack) = slot match {
    case slots.top => isValidPartialRecipe(Some(stack), Option(inv(slots.middle)), Option(inv(slots.bottom)))
    case slots.middle => isValidPartialRecipe(Option(inv(slots.top)), Some(stack), Option(inv(slots.bottom)))
    case slots.bottom => isValidPartialRecipe(Option(inv(slots.top)), Option(inv(slots.middle)), Some(stack))
    case _ => false
  }

  override def canExtractItem(slot: Int, stack: ItemStack, side: EnumFacing) = slot match {
    case slots.output => true
    case slots.top => (!topLocked) && output.isDefined && inv(slots.middle) == null
    case slots.bottom => (!bottomLocked) && output.isDefined && inv(slots.middle) == null
    case _ => false
  }

  override def shouldRefresh(world: World, pos: BlockPos, oldState: IBlockState, newSate: IBlockState): Boolean = newSate.getBlock != BlockInscriber

  onWake.listen(() => BlockInscriber.setActive(worldObj, pos, true))
  onSleep.listen(() => BlockInscriber.setActive(worldObj, pos, false))
}
