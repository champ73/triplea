package games.strategy.triplea.ai.proAI;

/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.proAI.util.LogUtils;
import games.strategy.triplea.ai.proAI.util.ProAttackOptionsUtils;
import games.strategy.triplea.ai.proAI.util.ProBattleUtils;
import games.strategy.triplea.ai.proAI.util.ProMatches;
import games.strategy.triplea.ai.proAI.util.ProMoveUtils;
import games.strategy.triplea.ai.proAI.util.ProPurchaseUtils;
import games.strategy.triplea.ai.proAI.util.ProTerritoryValueUtils;
import games.strategy.triplea.ai.proAI.util.ProTransportUtils;
import games.strategy.triplea.ai.proAI.util.ProUtils;
import games.strategy.triplea.attatchments.RulesAttachment;
import games.strategy.triplea.attatchments.TerritoryAttachment;
import games.strategy.triplea.attatchments.UnitAttachment;
import games.strategy.triplea.delegate.BattleCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.util.IntegerMap;
import games.strategy.util.Match;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Pro purchase AI.
 * 
 * <ol>
 * <li>Consider movable factories correctly and store where they should end up</li>
 * <li>Add logic to consider 2 turn transport attacks</li>
 * <li>Consider V1 rules (unlimited production)</li>
 * <li>Consider whether factory should be sea vs land</li>
 * </ol>
 * 
 * @author Ron Murhammer
 * @since 2014
 */
public class ProPurchaseAI
{
	public final static double WIN_PERCENTAGE = 95.0;
	
	// Utilities
	private final ProUtils utils;
	private final ProBattleUtils battleUtils;
	private final ProTransportUtils transportUtils;
	private final ProAttackOptionsUtils attackOptionsUtils;
	private final ProMoveUtils moveUtils;
	private final ProTerritoryValueUtils territoryValueUtils;
	private final ProPurchaseUtils purchaseUtils;
	
	// Current map settings
	private boolean areNeutralsPassableByAir;
	
	// Current data
	private GameData data;
	private PlayerID player;
	private Territory myCapital;
	private List<Territory> allTerritories;
	private Map<Unit, Territory> unitTerritoryMap;
	
	public ProPurchaseAI(final ProUtils utils, final ProBattleUtils battleUtils, final ProTransportUtils transportUtils, final ProAttackOptionsUtils attackOptionsUtils,
				final ProMoveUtils moveUtils, final ProTerritoryValueUtils territoryValueUtils, final ProPurchaseUtils purchaseUtils)
	{
		this.utils = utils;
		this.battleUtils = battleUtils;
		this.transportUtils = transportUtils;
		this.attackOptionsUtils = attackOptionsUtils;
		this.moveUtils = moveUtils;
		this.territoryValueUtils = territoryValueUtils;
		this.purchaseUtils = purchaseUtils;
	}
	
	public void bid(final int PUsToSpend, final IPurchaseDelegate purchaseDelegate, final GameData data, final PlayerID player)
	{
		LogUtils.log(Level.FINE, "Starting bid purchase phase");
		
		// Current data at the start of combat move
		this.data = data;
		this.player = player;
		areNeutralsPassableByAir = (Properties.getNeutralFlyoverAllowed(data) && !Properties.getNeutralsImpassable(data));
		myCapital = TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data);
		allTerritories = data.getMap().getTerritories();
	}
	
	public Map<Territory, ProPurchaseTerritory> purchase(final int PUsToSpend, final IPurchaseDelegate purchaseDelegate, final GameData data, final PlayerID player)
	{
		LogUtils.log(Level.FINE, "Starting purchase phase");
		
		// Current data at the start of combat move
		this.data = data;
		this.player = player;
		areNeutralsPassableByAir = (Properties.getNeutralFlyoverAllowed(data) && !Properties.getNeutralsImpassable(data));
		myCapital = TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data);
		allTerritories = data.getMap().getTerritories();
		int PUsRemaining = PUsToSpend;
		
		// Find all purchase options
		final List<ProPurchaseOption> specialPurchaseOptions = new ArrayList<ProPurchaseOption>();
		final List<ProPurchaseOption> factoryPurchaseOptions = new ArrayList<ProPurchaseOption>();
		final List<ProPurchaseOption> landPurchaseOptions = new ArrayList<ProPurchaseOption>();
		final List<ProPurchaseOption> airPurchaseOptions = new ArrayList<ProPurchaseOption>();
		final List<ProPurchaseOption> seaPurchaseOptions = new ArrayList<ProPurchaseOption>();
		purchaseUtils.findPurchaseOptions(player, landPurchaseOptions, airPurchaseOptions, seaPurchaseOptions, factoryPurchaseOptions, specialPurchaseOptions);
		
		// Find all purchase territories
		final Map<Territory, ProPurchaseTerritory> purchaseTerritories = findPurchaseTerritories();
		final Set<Territory> placeTerritories = new HashSet<Territory>();
		placeTerritories.addAll(Match.getMatches(data.getMap().getTerritoriesOwnedBy(player), Matches.TerritoryIsLand));
		for (final Territory t : purchaseTerritories.keySet())
		{
			for (final ProPlaceTerritory ppt : purchaseTerritories.get(t).getCanPlaceTerritories())
			{
				placeTerritories.add(ppt.getTerritory());
			}
		}
		
		// Determine max enemy attack units
		final Map<Territory, ProAttackTerritoryData> enemyAttackMap = new HashMap<Territory, ProAttackTerritoryData>();
		attackOptionsUtils.findMaxEnemyAttackUnits(player, new ArrayList<Territory>(), new ArrayList<Territory>(placeTerritories), enemyAttackMap);
		
		// Prioritize territories that need defended and purchase additional defenders
		findDefendersInPlaceTerritories(purchaseTerritories);
		final List<ProPlaceTerritory> needToDefendTerritories = prioritizeTerritoriesToDefend(purchaseTerritories, enemyAttackMap);
		PUsRemaining = purchaseDefenders(purchaseTerritories, enemyAttackMap, needToDefendTerritories, PUsRemaining, landPurchaseOptions);
		
		// Find strategic value for each territory
		LogUtils.log(Level.FINE, "Find strategic value for place territories");
		final Map<Territory, Double> territoryValueMap = territoryValueUtils.findTerritoryValues(player, new ArrayList<Territory>());
		for (final Territory t : purchaseTerritories.keySet())
		{
			for (final ProPlaceTerritory ppt : purchaseTerritories.get(t).getCanPlaceTerritories())
			{
				ppt.setStrategicValue(territoryValueMap.get(ppt.getTerritory()));
				LogUtils.log(Level.FINER, ppt.getTerritory() + ", strategicValue=" + territoryValueMap.get(ppt.getTerritory()));
			}
		}
		
		// Prioritize land place options and purchase units
		final List<ProPlaceTerritory> prioritizedLandTerritories = prioritizeLandTerritories(purchaseTerritories);
		PUsRemaining = purchaseLandUnits(purchaseTerritories, enemyAttackMap, prioritizedLandTerritories, PUsRemaining, landPurchaseOptions);
		
		// Determine whether to purchase new land factory
		final Map<Territory, ProPurchaseTerritory> factoryPurchaseTerritories = new HashMap<Territory, ProPurchaseTerritory>();
		PUsRemaining = purchaseFactory(factoryPurchaseTerritories, enemyAttackMap, PUsRemaining, factoryPurchaseOptions, prioritizedLandTerritories, landPurchaseOptions, false);
		
		// Prioritize sea place options and purchase units
		final List<ProPlaceTerritory> prioritizedSeaTerritories = prioritizeSeaTerritories(purchaseTerritories);
		PUsRemaining = purchaseSeaUnits(purchaseTerritories, enemyAttackMap, prioritizedSeaTerritories, PUsRemaining, seaPurchaseOptions, landPurchaseOptions);
		
		// If a land factory wasn't purchase then try to purchase a sea factory
		if (factoryPurchaseTerritories.isEmpty())
			PUsRemaining = purchaseFactory(factoryPurchaseTerritories, enemyAttackMap, PUsRemaining, factoryPurchaseOptions, prioritizedLandTerritories, landPurchaseOptions, true);
		
		// Try to use any remaining PUs to upgrade land units
		PUsRemaining = spendRemainingPUs(purchaseTerritories, PUsRemaining, landPurchaseOptions, airPurchaseOptions);
		
		// Add factory purchase territory to list if not null
		if (!factoryPurchaseTerritories.isEmpty())
			purchaseTerritories.putAll(factoryPurchaseTerritories);
		
		// Determine final count of each production rule
		final IntegerMap<ProductionRule> purchaseMap = populateProductionRuleMap(purchaseTerritories, landPurchaseOptions, airPurchaseOptions, seaPurchaseOptions, factoryPurchaseOptions);
		
		// Purchase units
		purchaseDelegate.purchase(purchaseMap);
		
		return purchaseTerritories;
	}
	
	public void place(final Map<Territory, ProPurchaseTerritory> purchaseTerritories, final IAbstractPlaceDelegate placeDelegate, final GameData data, final PlayerID player)
	{
		LogUtils.log(Level.FINE, "Starting place phase");
		
		for (final Territory t : purchaseTerritories.keySet())
		{
			for (final ProPlaceTerritory ppt : purchaseTerritories.get(t).getCanPlaceTerritories())
			{
				final Collection<Unit> myUnits = player.getUnits().getUnits();
				final Collection<Unit> unitsToPlace = new ArrayList<Unit>();
				for (final Unit placeUnit : ppt.getPlaceUnits())
				{
					for (final Unit myUnit : myUnits)
					{
						if (myUnit.getUnitType().equals(placeUnit.getUnitType()) && !unitsToPlace.contains(myUnit))
						{
							unitsToPlace.add(myUnit);
							break;
						}
					}
				}
				doPlace(data.getMap().getTerritory(ppt.getTerritory().getName()), unitsToPlace, placeDelegate);
				LogUtils.log(Level.FINER, ppt.getTerritory() + " placed units: " + unitsToPlace);
			}
		}
	}
	
	private Map<Territory, ProPurchaseTerritory> findPurchaseTerritories()
	{
		LogUtils.log(Level.FINE, "Find all purchase territories");
		
		// Find all territories that I can place units on
		final RulesAttachment ra = (RulesAttachment) player.getAttachment(Constants.RULES_ATTACHMENT_NAME);
		List<Territory> ownedAndNotConqueredFactoryTerritories = new ArrayList<Territory>();
		if (ra != null && ra.getPlacementAnyTerritory()) // make them all available for placing
			ownedAndNotConqueredFactoryTerritories = data.getMap().getTerritoriesOwnedBy(player);
		else
			ownedAndNotConqueredFactoryTerritories = Match.getMatches(data.getMap().getTerritories(), ProMatches.territoryHasInfraFactoryAndIsNotConqueredOwnedLand(player, data));
		
		// Create purchase territory holder for each factory territory
		final Map<Territory, ProPurchaseTerritory> purchaseTerritories = new HashMap<Territory, ProPurchaseTerritory>();
		for (final Territory t : ownedAndNotConqueredFactoryTerritories)
		{
			final ProPurchaseTerritory ppt = new ProPurchaseTerritory(t, data, player);
			purchaseTerritories.put(t, ppt);
			LogUtils.log(Level.FINER, ppt.toString());
		}
		
		return purchaseTerritories;
	}
	
	private void findDefendersInPlaceTerritories(final Map<Territory, ProPurchaseTerritory> purchaseTerritories)
	{
		LogUtils.log(Level.FINE, "Find defenders in possible place territories");
		
		for (final ProPurchaseTerritory ppt : purchaseTerritories.values())
		{
			for (final ProPlaceTerritory placeTerritory : ppt.getCanPlaceTerritories())
			{
				final Territory t = placeTerritory.getTerritory();
				final List<Unit> units = t.getUnits().getMatches(Matches.isUnitAllied(player, data));
				placeTerritory.setDefendingUnits(units);
				LogUtils.log(Level.FINER, t + " has numDefenders=" + units.size());
			}
		}
	}
	
	private List<ProPlaceTerritory> prioritizeTerritoriesToDefend(final Map<Territory, ProPurchaseTerritory> purchaseTerritories, final Map<Territory, ProAttackTerritoryData> enemyAttackMap)
	{
		LogUtils.log(Level.FINE, "Prioritize territories to defend");
		
		// Determine which territories need defended
		final List<ProPlaceTerritory> needToDefendTerritories = new ArrayList<ProPlaceTerritory>();
		for (final ProPurchaseTerritory ppt : purchaseTerritories.values())
		{
			// Check if any of the place territories can't be held with current defenders
			for (final ProPlaceTerritory placeTerritory : ppt.getCanPlaceTerritories())
			{
				final Territory t = placeTerritory.getTerritory();
				if (enemyAttackMap.get(t) == null || t.isWater())
					continue;
				
				// Find current battle result
				final Set<Unit> enemyAttackingUnits = new HashSet<Unit>(enemyAttackMap.get(t).getMaxUnits());
				enemyAttackingUnits.addAll(enemyAttackMap.get(t).getMaxAmphibUnits());
				final ProBattleResultData result = battleUtils.calculateBattleResults(player, t, new ArrayList<Unit>(enemyAttackingUnits), placeTerritory.getDefendingUnits(), false);
				placeTerritory.setMinBattleResult(result);
				
				// If it can't currently be held then add to list
				if (result.isHasLandUnitRemaining() || result.getTUVSwing() > 0 || (t.equals(myCapital) && result.getWinPercentage() > (100 - WIN_PERCENTAGE)))
					needToDefendTerritories.add(placeTerritory);
			}
		}
		
		// Calculate value of defending territory
		final IntegerMap<UnitType> playerCostMap = BattleCalculator.getCostsForTUV(player, data);
		for (final ProPlaceTerritory placeTerritory : needToDefendTerritories)
		{
			final Territory t = placeTerritory.getTerritory();
			
			// Determine if it is my capital or adjacent to my capital
			int isMyCapital = 0;
			if (t.equals(myCapital))
				isMyCapital = 1;
			
			// Determine if it has a factory
			int isFactory = 0;
			if (t.getUnits().someMatch(Matches.UnitCanProduceUnits))
				isFactory = 1;
			
			// Determine production value and if it is an enemy capital
			int production = 0;
			final TerritoryAttachment ta = TerritoryAttachment.get(t);
			if (ta != null)
			{
				production = ta.getProduction();
			}
			
			// Calculate defense value for prioritization
			final int defendingUnitValue = BattleCalculator.getTUV(placeTerritory.getDefendingUnits(), playerCostMap);
			final double territoryValue = (2 * production + 5 * isFactory + 0.5 * defendingUnitValue) * (1 + 10 * isMyCapital);
			placeTerritory.setDefenseValue(territoryValue);
		}
		
		// Sort territories by value
		Collections.sort(needToDefendTerritories, new Comparator<ProPlaceTerritory>()
		{
			public int compare(final ProPlaceTerritory t1, final ProPlaceTerritory t2)
			{
				final double value1 = t1.getDefenseValue();
				final double value2 = t2.getDefenseValue();
				return Double.compare(value2, value1);
			}
		});
		
		for (final ProPlaceTerritory placeTerritory : needToDefendTerritories)
			LogUtils.log(Level.FINER, placeTerritory.toString() + " defenseValue=" + placeTerritory.getDefenseValue());
		
		return needToDefendTerritories;
	}
	
	private int purchaseDefenders(final Map<Territory, ProPurchaseTerritory> purchaseTerritories, final Map<Territory, ProAttackTerritoryData> enemyAttackMap,
				final List<ProPlaceTerritory> needToDefendTerritories, int PUsRemaining, final List<ProPurchaseOption> landPurchaseOptions)
	{
		LogUtils.log(Level.FINE, "Purchase defenders with PUsRemaining=" + PUsRemaining);
		
		// Loop through prioritized territories and purchase defenders
		for (final ProPlaceTerritory placeTerritory : needToDefendTerritories)
		{
			final Territory t = placeTerritory.getTerritory();
			LogUtils.log(Level.FINER, "Purchasing defenders for " + t.getName() + ", landEnemyAttackers=" + enemyAttackMap.get(t).getMaxUnits() + ", amphibEnemyAttackers="
						+ enemyAttackMap.get(t).getMaxAmphibUnits());
			
			// Determine most cost efficient defender that can be produced in this territory
			final List<ProPurchaseOption> purchaseOptionsForTerritory = purchaseUtils.findPurchaseOptionsForTerritory(player, landPurchaseOptions, t);
			ProPurchaseOption bestDefenseOption = null;
			double maxDefenseEfficiency = 0;
			for (final ProPurchaseOption ppo : purchaseOptionsForTerritory)
			{
				if (ppo.getDefenseEfficiency() > maxDefenseEfficiency && ppo.getCost() <= PUsRemaining)
				{
					bestDefenseOption = ppo;
					maxDefenseEfficiency = ppo.getDefenseEfficiency();
				}
			}
			if (bestDefenseOption == null)
				continue;
			LogUtils.log(Level.FINER, "Best defense option: " + bestDefenseOption.getUnitType().getName());
			
			int remainingUnitProduction = purchaseTerritories.get(t).getUnitProduction();
			int PUsSpent = 0;
			ProBattleResultData finalResult = new ProBattleResultData();
			while (true)
			{
				// If out of PUs or production then break
				if (bestDefenseOption == null || bestDefenseOption.getCost() > (PUsRemaining - PUsSpent) || remainingUnitProduction < bestDefenseOption.getQuantity())
					break;
				
				// Create new temp defenders
				PUsSpent += bestDefenseOption.getCost();
				remainingUnitProduction -= bestDefenseOption.getQuantity();
				placeTerritory.getPlaceUnits().addAll(bestDefenseOption.getUnitType().create(bestDefenseOption.getQuantity(), player, true));
				
				// Find current battle result
				final Set<Unit> enemyAttackingUnits = new HashSet<Unit>(enemyAttackMap.get(t).getMaxUnits());
				enemyAttackingUnits.addAll(enemyAttackMap.get(t).getMaxAmphibUnits());
				finalResult = battleUtils.estimateDefendBattleResults(player, t, new ArrayList<Unit>(enemyAttackingUnits), placeTerritory.getAllDefenders());
				
				// If it can't currently be held then add to list
				if ((!t.equals(myCapital) && !finalResult.isHasLandUnitRemaining() && finalResult.getTUVSwing() <= 0) ||
							(t.equals(myCapital) && finalResult.getWinPercentage() < (100 - WIN_PERCENTAGE) && finalResult.getTUVSwing() <= 0))
					break;
			}
			
			// Check to see if its worth trying to defend the territory
			// if (!finalResult.isHasLandUnitRemaining() || finalResult.getTUVSwing() < placeTerritory.getMinBattleResult().getTUVSwing() || placeTerritory.equals(myCapital))
			PUsRemaining -= PUsSpent;
			// else
			// placeTerritory.getPlaceUnits().clear();
		}
		for (final ProPlaceTerritory placeTerritory : needToDefendTerritories)
			LogUtils.log(Level.FINER, placeTerritory.toString() + " placedUnits=" + placeTerritory.getPlaceUnits());
		
		return PUsRemaining;
	}
	
	private List<ProPlaceTerritory> prioritizeLandTerritories(final Map<Territory, ProPurchaseTerritory> purchaseTerritories)
	{
		LogUtils.log(Level.FINE, "Prioritize land territories to place");
		
		// Get all land place territories
		final List<ProPlaceTerritory> prioritizedLandTerritories = new ArrayList<ProPlaceTerritory>();
		for (final ProPurchaseTerritory ppt : purchaseTerritories.values())
		{
			for (final ProPlaceTerritory placeTerritory : ppt.getCanPlaceTerritories())
			{
				final Territory t = placeTerritory.getTerritory();
				if (!t.isWater() && placeTerritory.getStrategicValue() >= 0.25)
					prioritizedLandTerritories.add(placeTerritory);
			}
		}
		
		// Sort territories by value
		Collections.sort(prioritizedLandTerritories, new Comparator<ProPlaceTerritory>()
		{
			public int compare(final ProPlaceTerritory t1, final ProPlaceTerritory t2)
			{
				final double value1 = t1.getStrategicValue();
				final double value2 = t2.getStrategicValue();
				return Double.compare(value2, value1);
			}
		});
		
		for (final ProPlaceTerritory placeTerritory : prioritizedLandTerritories)
			LogUtils.log(Level.FINER, placeTerritory.toString() + " strategicValue=" + placeTerritory.getStrategicValue());
		
		return prioritizedLandTerritories;
	}
	
	private int purchaseLandUnits(final Map<Territory, ProPurchaseTerritory> purchaseTerritories, final Map<Territory, ProAttackTerritoryData> enemyAttackMap,
				final List<ProPlaceTerritory> prioritizedLandTerritories, int PUsRemaining, final List<ProPurchaseOption> landPurchaseOptions)
	{
		LogUtils.log(Level.FINE, "Purchase land units with PUsRemaining=" + PUsRemaining);
		
		// Loop through prioritized territories and purchase land units
		for (final ProPlaceTerritory placeTerritory : prioritizedLandTerritories)
		{
			final Territory t = placeTerritory.getTerritory();
			
			// Determine units that can be produced in this territory
			final List<ProPurchaseOption> purchaseOptionsForTerritory = purchaseUtils.findPurchaseOptionsForTerritory(player, landPurchaseOptions, t);
			
			// Determine most cost efficient units
			ProPurchaseOption bestHitPointOption = null;
			double maxHitPointEfficiency = 0;
			ProPurchaseOption bestAttackOption = null;
			double maxAttackEfficiency = 0;
			ProPurchaseOption bestTwoMoveOption = null;
			double maxTwoMoveEfficiency = 0;
			ProPurchaseOption bestThreeMoveOption = null;
			double maxThreeMoveEfficiency = 0;
			for (final ProPurchaseOption ppo : purchaseOptionsForTerritory)
			{
				if (ppo.getCost() <= PUsRemaining)
				{
					if (ppo.getHitPointEfficiency() > maxHitPointEfficiency)
					{
						bestHitPointOption = ppo;
						maxHitPointEfficiency = ppo.getHitPointEfficiency();
					}
					if (ppo.getAttackEfficiency() > maxAttackEfficiency)
					{
						bestAttackOption = ppo;
						maxAttackEfficiency = ppo.getAttackEfficiency();
					}
					if (ppo.getMovement() >= 2 && ppo.getAttackEfficiency() > maxTwoMoveEfficiency)
					{
						bestTwoMoveOption = ppo;
						maxTwoMoveEfficiency = ppo.getAttackEfficiency();
					}
					if (ppo.getMovement() >= 3 && ppo.getAttackEfficiency() > maxThreeMoveEfficiency)
					{
						bestThreeMoveOption = ppo;
						maxThreeMoveEfficiency = ppo.getAttackEfficiency();
					}
				}
			}
			
			// Check if there aren't enough PUs to buy any units
			if (bestHitPointOption == null || bestAttackOption == null)
				continue;
			
			LogUtils.log(Level.FINER, "Best hit point unit: " + bestHitPointOption.getUnitType().getName());
			LogUtils.log(Level.FINER, "Best attack unit:" + bestAttackOption.getUnitType().getName());
			if (bestTwoMoveOption != null)
				LogUtils.log(Level.FINER, "Best two move unit: " + bestTwoMoveOption.getUnitType().getName());
			if (bestThreeMoveOption != null)
				LogUtils.log(Level.FINER, "Best three move unit: " + bestThreeMoveOption.getUnitType().getName());
			
			// Get optimal unit combo based on distance from enemy
			final int enemyDistance = utils.getClosestEnemyLandTerritoryDistance(data, player, t);
			if (enemyDistance <= 0)
				continue;
			int hitPointPercent = 65;
			int attackPercent = 35;
			int twoMovePercent = 0;
			int threeMovePercent = 0;
			if (bestThreeMoveOption != null && enemyDistance > 4)
				threeMovePercent = 50;
			else if (bestTwoMoveOption != null)
				twoMovePercent = Math.min(50, 10 * enemyDistance);
			if (threeMovePercent + twoMovePercent > attackPercent)
			{
				hitPointPercent = 100 - (threeMovePercent + twoMovePercent);
				attackPercent = 0;
			}
			else
			{
				attackPercent = attackPercent - (threeMovePercent + twoMovePercent);
			}
			LogUtils.log(Level.FINEST, t + ", enemyDistance=" + enemyDistance + ", hitPointPercent=" + hitPointPercent + ", attackPercent=" + attackPercent + ", twoMovePercent=" + twoMovePercent
						+ " threeMovePercent=" + threeMovePercent);
			
			// Find optimal units for remaining production
			int remainingUnitProduction = purchaseTerritories.get(t).getRemainingUnitProduction();
			final int numHitPointUnits = (int) Math.ceil(hitPointPercent / 100.0 * remainingUnitProduction);
			final int numAttackUnits = (int) Math.ceil(attackPercent / 100.0 * remainingUnitProduction);
			final int numTwoMoveUnits = (int) Math.ceil(twoMovePercent / 100.0 * remainingUnitProduction);
			final int numThreeMoveUnits = (int) Math.ceil(threeMovePercent / 100.0 * remainingUnitProduction);
			
			// Purchase as many units as possible
			int addedHitPointUnits = 0;
			int addedAttackUnits = 0;
			int addedTwoMoveUnits = 0;
			int addedThreeMoveUnits = 0;
			final List<Unit> unitsToPlace = new ArrayList<Unit>();
			while (true)
			{
				// Find current purchase option
				ProPurchaseOption ppo = bestHitPointOption;
				if (addedHitPointUnits < numHitPointUnits && bestHitPointOption.getCost() <= PUsRemaining && remainingUnitProduction >= bestHitPointOption.getQuantity())
				{
					addedHitPointUnits += ppo.getQuantity();
				}
				else if (addedThreeMoveUnits < numThreeMoveUnits && bestThreeMoveOption.getCost() <= PUsRemaining && remainingUnitProduction >= bestThreeMoveOption.getQuantity())
				{
					ppo = bestThreeMoveOption;
					addedThreeMoveUnits += ppo.getQuantity();
				}
				else if (addedTwoMoveUnits < numTwoMoveUnits && bestTwoMoveOption.getCost() <= PUsRemaining && remainingUnitProduction >= bestTwoMoveOption.getQuantity())
				{
					ppo = bestTwoMoveOption;
					addedTwoMoveUnits += ppo.getQuantity();
				}
				else if (addedAttackUnits < numAttackUnits && bestAttackOption.getCost() <= PUsRemaining && remainingUnitProduction >= bestAttackOption.getQuantity())
				{
					ppo = bestAttackOption;
					addedAttackUnits += ppo.getQuantity();
				}
				else
				{
					break;
				}
				
				// Create new temp units
				PUsRemaining -= ppo.getCost();
				remainingUnitProduction -= ppo.getQuantity();
				unitsToPlace.addAll(ppo.getUnitType().create(ppo.getQuantity(), player, true));
			}
			
			// Add units to place territory
			placeTerritory.getPlaceUnits().addAll(unitsToPlace);
			LogUtils.log(Level.FINEST, t + ", placedUnits=" + unitsToPlace);
		}
		
		return PUsRemaining;
	}
	
	private List<ProPlaceTerritory> prioritizeSeaTerritories(final Map<Territory, ProPurchaseTerritory> purchaseTerritories)
	{
		LogUtils.log(Level.FINE, "Prioritize sea territories");
		
		// Determine which sea territories can be placed in
		final List<ProPlaceTerritory> seaPlaceTerritories = new ArrayList<ProPlaceTerritory>();
		for (final ProPurchaseTerritory ppt : purchaseTerritories.values())
		{
			for (final ProPlaceTerritory placeTerritory : ppt.getCanPlaceTerritories())
			{
				final Territory t = placeTerritory.getTerritory();
				if (t.isWater() && placeTerritory.getStrategicValue() > 0)
				{
					boolean alreadyAdded = false;
					for (final ProPlaceTerritory addedPlaceTerritory : seaPlaceTerritories)
					{
						if (t.equals(addedPlaceTerritory.getTerritory()))
							alreadyAdded = true;
					}
					if (!alreadyAdded)
						seaPlaceTerritories.add(placeTerritory);
				}
			}
		}
		
		// Calculate value of defending territory
		final IntegerMap<UnitType> playerCostMap = BattleCalculator.getCostsForTUV(player, data);
		for (final ProPlaceTerritory placeTerritory : seaPlaceTerritories)
		{
			// Calculate defense value for prioritization
			final int defendingUnitValue = BattleCalculator.getTUV(placeTerritory.getDefendingUnits(), playerCostMap);
			final double territoryValue = defendingUnitValue + placeTerritory.getStrategicValue();
			placeTerritory.setStrategicValue(territoryValue);
		}
		
		// Sort territories by value
		Collections.sort(seaPlaceTerritories, new Comparator<ProPlaceTerritory>()
		{
			public int compare(final ProPlaceTerritory t1, final ProPlaceTerritory t2)
			{
				final double value1 = t1.getStrategicValue();
				final double value2 = t2.getStrategicValue();
				return Double.compare(value2, value1);
			}
		});
		
		for (final ProPlaceTerritory placeTerritory : seaPlaceTerritories)
			LogUtils.log(Level.FINER, placeTerritory.toString() + " seaValue=" + placeTerritory.getStrategicValue());
		
		return seaPlaceTerritories;
	}
	
	private int purchaseSeaUnits(final Map<Territory, ProPurchaseTerritory> purchaseTerritories, final Map<Territory, ProAttackTerritoryData> enemyAttackMap,
				final List<ProPlaceTerritory> prioritizedSeaTerritories, int PUsRemaining, final List<ProPurchaseOption> seaPurchaseOptions, final List<ProPurchaseOption> landPurchaseOptions)
	{
		LogUtils.log(Level.FINE, "Purchase sea units with PUsRemaining=" + PUsRemaining);
		
		// Loop through prioritized territories and purchase sea units
		for (final ProPlaceTerritory placeTerritory : prioritizedSeaTerritories)
		{
			final Territory t = placeTerritory.getTerritory();
			LogUtils.log(Level.FINER, "Checking sea place for territory: " + t.getName());
			
			// Determine units that can be produced in this territory
			final List<ProPurchaseOption> seaPurchaseOptionsForTerritory = purchaseUtils.findPurchaseOptionsForTerritory(player, seaPurchaseOptions, t);
			final Set<ProPurchaseOption> landPurchaseOptionsForTerritory = new HashSet<ProPurchaseOption>();
			for (final Territory purchaseTerritory : purchaseTerritories.keySet())
			{
				for (final ProPlaceTerritory ppt : purchaseTerritories.get(purchaseTerritory).getCanPlaceTerritories())
				{
					// If place territory is equal to the current place territory
					if (t.equals(ppt.getTerritory()) && purchaseTerritories.get(purchaseTerritory).getRemainingUnitProduction() > 0)
						landPurchaseOptionsForTerritory.addAll(purchaseUtils.findPurchaseOptionsForTerritory(player, landPurchaseOptions, purchaseTerritory));
				}
			}
			LogUtils.log(Level.FINER, "Sea options: " + seaPurchaseOptionsForTerritory.size());
			LogUtils.log(Level.FINER, "Land options: " + landPurchaseOptionsForTerritory.size());
			
			// Determine most cost efficient sea units
			ProPurchaseOption bestTransportOption = null;
			double maxTransportEfficiency = 0;
			ProPurchaseOption bestDefenseOption = null;
			double maxDefenseEfficiency = 0;
			for (final ProPurchaseOption ppo : seaPurchaseOptionsForTerritory)
			{
				if (ppo.getCost() <= PUsRemaining)
				{
					if (ppo.getTransportEfficiency() * ppo.getMovement() > maxTransportEfficiency)
					{
						bestTransportOption = ppo;
						maxTransportEfficiency = ppo.getTransportEfficiency() * ppo.getMovement();
					}
					if (!ppo.isSub() && ppo.getDefenseEfficiency() * ppo.getMovement() > maxDefenseEfficiency)
					{
						bestDefenseOption = ppo;
						maxDefenseEfficiency = ppo.getDefenseEfficiency() * ppo.getMovement();
					}
				}
			}
			
			// Determine most cost efficient amphib units (based on best hit point efficiency for now)
			ProPurchaseOption bestAmphibOption = null;
			double maxAmphibEfficiency = 0;
			for (final ProPurchaseOption ppo : landPurchaseOptionsForTerritory)
			{
				if (ppo.getCost() <= PUsRemaining)
				{
					if (ppo.getHitPointEfficiency() > maxAmphibEfficiency)
					{
						bestAmphibOption = ppo;
						maxAmphibEfficiency = ppo.getHitPointEfficiency();
					}
				}
			}
			
			// Check if there aren't enough PUs to buy any units
			if (bestTransportOption == null && bestDefenseOption == null && bestAmphibOption == null)
				continue;
			
			if (bestTransportOption != null)
				LogUtils.log(Level.FINER, "Best transport unit: " + bestTransportOption.getUnitType().getName());
			if (bestDefenseOption != null)
				LogUtils.log(Level.FINER, "Best defense unit: " + bestDefenseOption.getUnitType().getName());
			if (bestAmphibOption != null)
				LogUtils.log(Level.FINER, "Best amphib unit: " + bestAmphibOption.getUnitType().getName());
			
			// If I don't have enough PUs remaining to purchase anything then break
			if ((bestTransportOption == null || bestTransportOption.getCost() > PUsRemaining)
						&& (bestDefenseOption == null || bestDefenseOption.getCost() > PUsRemaining)
						&& (bestAmphibOption == null || bestAmphibOption.getCost() > PUsRemaining))
				break;
			
			// Find remaining production
			int usedProduction = 0;
			int remainingTransportProduction = 0;
			int remainingDefenseProduction = 0;
			int remainingAmphibProduction = 0;
			for (final Territory purchaseTerritory : purchaseTerritories.keySet())
			{
				for (final ProPlaceTerritory ppt : purchaseTerritories.get(purchaseTerritory).getCanPlaceTerritories())
				{
					if (t.equals(ppt.getTerritory()))
					{
						if (purchaseUtils.canTerritoryUsePurchaseOption(player, bestTransportOption, purchaseTerritory))
							remainingTransportProduction += purchaseTerritories.get(purchaseTerritory).getRemainingUnitProduction();
						if (purchaseUtils.canTerritoryUsePurchaseOption(player, bestDefenseOption, purchaseTerritory))
							remainingDefenseProduction += purchaseTerritories.get(purchaseTerritory).getRemainingUnitProduction();
						if (purchaseUtils.canTerritoryUsePurchaseOption(player, bestAmphibOption, purchaseTerritory))
							remainingAmphibProduction += purchaseTerritories.get(purchaseTerritory).getRemainingUnitProduction();
					}
				}
			}
			LogUtils.log(Level.FINER, t + ", remainingTransportProduction=" + remainingTransportProduction + ", remainingDefenseProduction=" + remainingDefenseProduction
						+ ", remainingAmphibProduction=" + remainingAmphibProduction);
			
			// If any enemy attackers then purchase sea defenders until it can be held
			if (enemyAttackMap.get(t) != null)
			{
				int PUsSpent = 0;
				final List<Unit> unitsToPlace = new ArrayList<Unit>();
				ProBattleResultData finalResult = battleUtils.estimateDefendBattleResults(player, t, enemyAttackMap.get(t).getMaxUnits(), placeTerritory.getAllDefenders());
				while (true)
				{
					// If out of PUs or production then break
					if (bestDefenseOption == null || bestDefenseOption.getCost() > (PUsRemaining - PUsSpent) || (remainingDefenseProduction - usedProduction) < bestDefenseOption.getQuantity())
						break;
					
					// If it can be held then break
					if (finalResult.getTUVSwing() < 0 || finalResult.getWinPercentage() < WIN_PERCENTAGE)
						break;
					
					// Create new temp defenders
					PUsSpent += bestDefenseOption.getCost();
					usedProduction += bestDefenseOption.getQuantity();
					unitsToPlace.addAll(bestDefenseOption.getUnitType().create(bestDefenseOption.getQuantity(), player, true));
					
					// Find current battle result
					final List<Unit> defendingUnits = new ArrayList<Unit>(placeTerritory.getAllDefenders());
					defendingUnits.addAll(unitsToPlace);
					finalResult = battleUtils.estimateDefendBattleResults(player, t, enemyAttackMap.get(t).getMaxUnits(), defendingUnits);
				}
				
				// Check to see if its worth trying to defend the territory
				if (finalResult.getTUVSwing() < 0 || finalResult.getWinPercentage() < WIN_PERCENTAGE)
				{
					PUsRemaining -= PUsSpent;
					placeTerritory.getPlaceUnits().addAll(unitsToPlace);
					LogUtils.log(Level.FINER, t + ", placedSeaDefenders=" + unitsToPlace);
				}
				else
				{
					LogUtils.log(Level.FINER, t + ", can't defend TUVSwing=" + finalResult.getTUVSwing() + ", win%=" + finalResult.getWinPercentage() + ", tried to placeDefenders=" + unitsToPlace
								+ ", enemyAttackers=" + enemyAttackMap.get(t).getMaxUnits());
					continue;
				}
			}
			
			// Make sure to have local naval superiority
			final Set<Territory> nearbyTerritories = data.getMap().getNeighbors(t, 4);
			final List<Territory> nearbyLandTerritories = Match.getMatches(nearbyTerritories, Matches.TerritoryIsLand);
			final Set<Territory> nearbySeaTerritories = data.getMap().getNeighbors(t, 4, Matches.TerritoryIsWater);
			nearbySeaTerritories.add(t);
			final List<Unit> enemyUnitsInSeaTerritories = new ArrayList<Unit>();
			final List<Unit> enemyUnitsInLandTerritories = new ArrayList<Unit>();
			final List<Unit> myUnitsInSeaTerritories = new ArrayList<Unit>();
			for (final Territory nearbyLandTerritory : nearbyLandTerritories)
			{
				enemyUnitsInLandTerritories.addAll(nearbyLandTerritory.getUnits().getMatches(ProMatches.unitIsEnemyAir(player, data)));
			}
			for (final Territory nearbySeaTerritory : nearbySeaTerritories)
			{
				enemyUnitsInSeaTerritories.addAll(nearbySeaTerritory.getUnits().getMatches(ProMatches.unitIsEnemyNotLand(player, data)));
				myUnitsInSeaTerritories.addAll(nearbySeaTerritory.getUnits().getMatches(ProMatches.unitIsOwnedNotLand(player, data)));
			}
			while (true)
			{
				// If out of PUs or production then break
				if (bestDefenseOption == null || bestDefenseOption.getCost() > PUsRemaining || (remainingDefenseProduction - usedProduction) < bestDefenseOption.getQuantity())
					break;
				
				// Find current naval defense strength
				final List<Unit> myUnits = new ArrayList<Unit>(myUnitsInSeaTerritories);
				myUnits.addAll(placeTerritory.getPlaceUnits());
				final List<Unit> enemyAttackers = new ArrayList<Unit>(enemyUnitsInSeaTerritories);
				enemyAttackers.addAll(enemyUnitsInLandTerritories);
				final double defenseStrengthDifference = battleUtils.estimateStrengthDifference(t, enemyAttackers, myUnits);
				LogUtils.log(Level.FINER, t + ", current enemy naval attack strengthDifference=" + defenseStrengthDifference + ", enemySize=" + enemyAttackers.size() + ", alliedSize="
							+ myUnits.size());
				
				// Find current naval attack strength
				final double attackStrengthDifference = battleUtils.estimateStrengthDifference(t, myUnits, enemyUnitsInSeaTerritories);
				LogUtils.log(Level.FINER, t + ", current allied naval attack strengthDifference=" + attackStrengthDifference + ", alliedSize=" + myUnits.size() + ", enemySize="
							+ enemyUnitsInSeaTerritories.size());
				
				// If I have naval attack/defense superiority then break
				if (defenseStrengthDifference < 50 && attackStrengthDifference > 50)
					break;
				
				// Create new temp units
				placeTerritory.getPlaceUnits().addAll(bestDefenseOption.getUnitType().create(bestDefenseOption.getQuantity(), player, true));
				PUsRemaining -= bestDefenseOption.getCost();
				usedProduction += bestDefenseOption.getQuantity();
				LogUtils.log(Level.FINER, t + ", added sea defender for naval superiority: " + bestDefenseOption.getUnitType().getName());
			}
			
			// If no amphib option then break
			if (bestAmphibOption == null)
				continue;
			
			// Determine whether transports, amphib units, or both are needed
			final List<Unit> transports = Match.getMatches(t.getUnits().getUnits(), ProMatches.unitIsOwnedTransport(player));
			int transportCapacity = 0;
			for (final Unit transport : transports)
			{
				transportCapacity += UnitAttachment.get(transport.getType()).getTransportCapacity() / bestAmphibOption.getTransportCost();
			}
			final int numUnitsToLoad = transportUtils.findNumUnitsThatCanBeTransported(player, t);
			int numNeededUnits = transportCapacity - numUnitsToLoad;
			LogUtils.log(Level.FINER, t + ", numNeededAmphibUnits=" + numNeededUnits + " = " + transportCapacity + " - " + numUnitsToLoad);
			
			// Purchase transports and amphib units
			final List<Unit> amphibUnitsToPlace = new ArrayList<Unit>();
			final List<Unit> transportUnitsToPlace = new ArrayList<Unit>();
			while (true)
			{
				// Find current purchase option
				ProPurchaseOption ppo = bestAmphibOption;
				if (numNeededUnits >= 0 && bestAmphibOption.getCost() <= PUsRemaining && (remainingAmphibProduction - usedProduction) >= bestAmphibOption.getQuantity())
				{
					numNeededUnits -= bestAmphibOption.getQuantity();
					amphibUnitsToPlace.addAll(ppo.getUnitType().create(ppo.getQuantity(), player, true));
				}
				else if (bestTransportOption != null && numNeededUnits < 0 && bestTransportOption.getCost() <= PUsRemaining
							&& (remainingTransportProduction - usedProduction) >= bestTransportOption.getQuantity())
				{
					ppo = bestTransportOption;
					numNeededUnits += bestTransportOption.getTransportCapacity() * bestTransportOption.getQuantity() / bestAmphibOption.getTransportCost();
					transportUnitsToPlace.addAll(ppo.getUnitType().create(ppo.getQuantity(), player, true));
				}
				else
				{
					break;
				}
				
				// Create new temp units
				PUsRemaining -= ppo.getCost();
				usedProduction += ppo.getQuantity();
			}
			
			// Add transport units to place territory and amphib units to adjacent factories
			for (final Territory purchaseTerritory : purchaseTerritories.keySet())
			{
				for (final ProPlaceTerritory ppt : purchaseTerritories.get(purchaseTerritory).getCanPlaceTerritories())
				{
					// If place territory is equal to the current place territory and has remaining production
					if (t.equals(ppt.getTerritory()) && purchaseTerritories.get(purchaseTerritory).getRemainingUnitProduction() > 0)
					{
						// Place transports
						final int numUnits = Math.min(purchaseTerritories.get(purchaseTerritory).getRemainingUnitProduction(), transportUnitsToPlace.size());
						final List<Unit> units = transportUnitsToPlace.subList(0, numUnits);
						ppt.getPlaceUnits().addAll(units);
						LogUtils.log(Level.FINER, t + ", transportUnits=" + transportUnitsToPlace);
						units.clear();
						
						for (final ProPlaceTerritory ppt2 : purchaseTerritories.get(purchaseTerritory).getCanPlaceTerritories())
						{
							// Find factory place territory
							if (purchaseTerritory.equals(ppt2.getTerritory()))
							{
								// Place amphib units
								final int numUnits2 = Math.min(purchaseTerritories.get(purchaseTerritory).getRemainingUnitProduction(), amphibUnitsToPlace.size());
								final List<Unit> units2 = amphibUnitsToPlace.subList(0, numUnits2);
								ppt2.getPlaceUnits().addAll(units2);
								LogUtils.log(Level.FINER, t + ", amphibUnits placed at " + purchaseTerritory + ": " + units);
								units2.clear();
								break;
							}
						}
					}
				}
			}
		}
		
		return PUsRemaining;
	}
	
	private int purchaseFactory(final Map<Territory, ProPurchaseTerritory> factoryPurchaseTerritories, final Map<Territory, ProAttackTerritoryData> enemyAttackMap, int PUsRemaining,
				final List<ProPurchaseOption> factoryPurchaseOptions, final List<ProPlaceTerritory> prioritizedLandTerritories, final List<ProPurchaseOption> landPurchaseOptions,
				final boolean allowSeaFactoryPurchase)
	{
		LogUtils.log(Level.FINE, "Purchase factory with PUsRemaining=" + PUsRemaining + ", purchaseSeaFactory=" + allowSeaFactoryPurchase);
		
		// Find all owned land territories that weren't conquered and don't already have a factory
		final List<Territory> possibleFactoryTerritories = Match.getMatches(data.getMap().getTerritories(), ProMatches.territoryHasNoInfraFactoryAndIsNotConqueredOwnedLand(player, data));
		final Set<Territory> purchaseFactoryTerritories = new HashSet<Territory>();
		final List<Territory> territoriesThatCantBeHeld = new ArrayList<Territory>();
		for (final Territory t : possibleFactoryTerritories)
		{
			// Only consider territories with production of 3 or more
			final int production = TerritoryAttachment.get(t).getProduction();
			if (production <= 2)
				continue;
			
			// Check if no enemy attackers and that it wasn't conquered this turn
			if (enemyAttackMap.get(t) == null)
			{
				purchaseFactoryTerritories.add(t);
				LogUtils.log(Level.FINEST, "Possible factory since no enemy attackers: " + t.getName());
			}
			else
			{
				// Find current battle result
				final List<Unit> defenders = t.getUnits().getMatches(Matches.isUnitAllied(player, data));
				final Set<Unit> enemyAttackingUnits = new HashSet<Unit>(enemyAttackMap.get(t).getMaxUnits());
				enemyAttackingUnits.addAll(enemyAttackMap.get(t).getMaxAmphibUnits());
				final ProBattleResultData result = battleUtils.estimateDefendBattleResults(player, t, new ArrayList<Unit>(enemyAttackingUnits), defenders);
				
				// Check if it can't be held or if it can then that it wasn't conquered this turn
				if (result.isHasLandUnitRemaining() || result.getTUVSwing() > 0)
				{
					territoriesThatCantBeHeld.add(t);
					LogUtils.log(Level.FINEST, "Can't hold territory: " + t.getName() + ", hasLandUnitRemaining=" + result.isHasLandUnitRemaining() + ", TUVSwing=" + result.getTUVSwing()
								+ ", enemyAttackers=" + enemyAttackingUnits.size() + ", myDefenders=" + defenders.size());
				}
				else
				{
					purchaseFactoryTerritories.add(t);
					LogUtils.log(Level.FINEST, "Possible factory: " + t.getName() + ", hasLandUnitRemaining=" + result.isHasLandUnitRemaining() + ", TUVSwing=" + result.getTUVSwing()
								+ ", enemyAttackers=" + enemyAttackingUnits.size() + ", myDefenders=" + defenders.size());
				}
			}
		}
		LogUtils.log(Level.FINER, "Possible factory territories: " + purchaseFactoryTerritories);
		
		// Remove any territories that don't have local land superiority
		for (final Iterator<Territory> it = purchaseFactoryTerritories.iterator(); it.hasNext();)
		{
			final Territory t = it.next();
			if (!battleUtils.territoryHasLocalLandSuperiority(t, 3, player))
				it.remove();
		}
		LogUtils.log(Level.FINER, "Possible factory territories that have land superiority: " + purchaseFactoryTerritories);
		
		// Find strategic value for each territory
		final Map<Territory, Double> territoryValueMap = territoryValueUtils.findTerritoryValues(player, territoriesThatCantBeHeld);
		double maxValue = 0.0;
		Territory maxTerritory = null;
		for (final Territory t : purchaseFactoryTerritories)
		{
			final int production = TerritoryAttachment.get(t).getProduction();
			final double value = territoryValueMap.get(t) * production + 0.1 * production;
			final boolean isAdjacentToSea = Matches.territoryHasNeighborMatching(data, Matches.TerritoryIsWater).match(t);
			LogUtils.log(Level.FINEST, t + ", strategic value=" + territoryValueMap.get(t) + ", value=" + value);
			if (value > maxValue && (territoryValueMap.get(t) >= 0.25 || (isAdjacentToSea && allowSeaFactoryPurchase)))
			{
				maxValue = value;
				maxTerritory = t;
			}
		}
		LogUtils.log(Level.FINER, "Try to purchase factory for territory: " + maxTerritory);
		
		// Determine whether to purchase factory
		if (maxTerritory != null)
		{
			// Determine units that can be produced in this territory
			final List<ProPurchaseOption> purchaseOptionsForTerritory = purchaseUtils.findPurchaseOptionsForTerritory(player, factoryPurchaseOptions, maxTerritory);
			
			// Find most expensive placed land unit to consider removing for a factory
			int maxPlacedCost = 0;
			ProPlaceTerritory maxPlacedTerritory = null;
			Unit maxPlacedUnit = null;
			for (final ProPlaceTerritory placeTerritory : prioritizedLandTerritories)
			{
				for (final Unit u : placeTerritory.getPlaceUnits())
				{
					for (final ProPurchaseOption ppo : landPurchaseOptions)
					{
						if (u.getType().equals(ppo.getUnitType()) && ppo.getQuantity() == 1 && ppo.getCost() >= maxPlacedCost)
						{
							maxPlacedCost = ppo.getCost();
							maxPlacedTerritory = placeTerritory;
							maxPlacedUnit = u;
						}
					}
				}
			}
			
			// Determine most expensive factory option (currently doesn't buy mobile factories)
			ProPurchaseOption bestFactoryOption = null;
			double maxFactoryEfficiency = 0;
			for (final ProPurchaseOption ppo : purchaseOptionsForTerritory)
			{
				if (ppo.getCost() <= (PUsRemaining + maxPlacedCost) && ppo.getMovement() == 0)
				{
					if (ppo.getCost() > maxFactoryEfficiency)
					{
						bestFactoryOption = ppo;
						maxFactoryEfficiency = ppo.getCost();
					}
				}
			}
			
			// Check if there are enough PUs to buy a factory
			if (bestFactoryOption != null)
			{
				LogUtils.log(Level.FINER, "Best factory unit: " + bestFactoryOption.getUnitType().getName());
				
				final ProPurchaseTerritory factoryPurchaseTerritory = new ProPurchaseTerritory(maxTerritory, data, player);
				factoryPurchaseTerritories.put(maxTerritory, factoryPurchaseTerritory);
				for (final ProPlaceTerritory ppt : factoryPurchaseTerritory.getCanPlaceTerritories())
				{
					if (ppt.getTerritory().equals(maxTerritory))
					{
						final List<Unit> factory = bestFactoryOption.getUnitType().create(bestFactoryOption.getQuantity(), player, true);
						ppt.getPlaceUnits().addAll(factory);
						if (PUsRemaining >= bestFactoryOption.getCost())
						{
							PUsRemaining -= bestFactoryOption.getCost();
							LogUtils.log(Level.FINER, maxTerritory + ", placedFactory=" + factory);
						}
						else
						{
							PUsRemaining -= (bestFactoryOption.getCost() - maxPlacedCost);
							maxPlacedTerritory.getPlaceUnits().remove(maxPlacedUnit);
							LogUtils.log(Level.FINER, maxTerritory + ", placedFactory=" + factory + ", removedUnit=" + maxPlacedUnit);
						}
					}
				}
			}
		}
		
		return PUsRemaining;
	}
	
	private int spendRemainingPUs(final Map<Territory, ProPurchaseTerritory> purchaseTerritories, int PUsRemaining, final List<ProPurchaseOption> landPurchaseOptions,
				final List<ProPurchaseOption> airPurchaseOptions)
	{
		LogUtils.log(Level.FINE, "Spend remaining PUs with PUsRemaining=" + PUsRemaining);
		
		// Loop through prioritized territories and purchase long range attack units
		for (final Territory t : purchaseTerritories.keySet())
		{
			LogUtils.log(Level.FINER, "Checking territory: " + t.getName());
			
			// Get place territory
			ProPlaceTerritory placeTerritory = null;
			for (final ProPlaceTerritory pt : purchaseTerritories.get(t).getCanPlaceTerritories())
			{
				if (pt.getTerritory().equals(t))
					placeTerritory = pt;
			}
			if (placeTerritory == null || placeTerritory.getTerritory().isWater())
				continue;
			
			// Determine units that can be produced in this territory
			final List<ProPurchaseOption> airAndLandPurchaseOptions = new ArrayList<ProPurchaseOption>(airPurchaseOptions);
			airAndLandPurchaseOptions.addAll(landPurchaseOptions);
			final List<ProPurchaseOption> purchaseOptionsForTerritory = purchaseUtils.findPurchaseOptionsForTerritory(player, airAndLandPurchaseOptions, t);
			
			// Find cheapest placed unit
			int minPlacedCost = Integer.MAX_VALUE;
			for (final Unit u : placeTerritory.getPlaceUnits())
			{
				for (final ProPurchaseOption ppo : landPurchaseOptions)
				{
					if (u.getType().equals(ppo.getUnitType()) && ppo.getQuantity() == 1 && ppo.getCost() < minPlacedCost)
					{
						minPlacedCost = ppo.getCost();
					}
				}
			}
			
			// Determine best long range attack option (prefer air units)
			ProPurchaseOption bestAttackOption = null;
			double maxAttackEfficiency = 0;
			for (final ProPurchaseOption ppo : purchaseOptionsForTerritory)
			{
				if (ppo.getCost() <= (PUsRemaining + minPlacedCost))
				{
					double attackEfficiency = ppo.getAttackEfficiency() * ppo.getMovement();
					if (ppo.isAir())
						attackEfficiency *= 10;
					if (ppo.getQuantity() == 1 && attackEfficiency > maxAttackEfficiency)
					{
						bestAttackOption = ppo;
						maxAttackEfficiency = ppo.getAttackEfficiency();
					}
				}
			}
			
			// Check if there aren't enough PUs to buy any units
			if (bestAttackOption == null)
				continue;
			
			LogUtils.log(Level.FINER, "Best long range attack unit: " + bestAttackOption.getUnitType().getName());
			
			// Replace land units with long range attack units
			final int maxAttackUnits = purchaseTerritories.get(t).getUnitProduction() / 3;
			final List<Unit> unitsToPlace = new ArrayList<Unit>();
			while (true)
			{
				// Find current purchase option
				final ProPurchaseOption ppo = bestAttackOption;
				if (unitsToPlace.size() < maxAttackUnits && !placeTerritory.getPlaceUnits().isEmpty() && bestAttackOption.getCost() <= (PUsRemaining + minPlacedCost))
				{
					// Find unit to replace with long range attack unit
					Unit unitToRemove = null;
					for (final Unit u : placeTerritory.getPlaceUnits())
					{
						for (final ProPurchaseOption ppo2 : landPurchaseOptions)
						{
							if (u.getType().equals(ppo2.getUnitType()) && ppo2.getQuantity() == 1 && ppo.getCost() > ppo2.getCost())
							{
								final List<Unit> newUnit = ppo.getUnitType().create(ppo.getQuantity(), player, true);
								unitsToPlace.addAll(newUnit);
								PUsRemaining -= (ppo.getCost() - ppo2.getCost());
								unitToRemove = u;
								LogUtils.log(Level.FINEST, t + ", addedUnit=" + newUnit + ", removedUnit=" + unitToRemove);
								break;
							}
						}
						if (unitToRemove != null)
							break;
					}
					if (unitToRemove != null)
						placeTerritory.getPlaceUnits().remove(unitToRemove);
					else
						break;
				}
				else
				{
					break;
				}
			}
			placeTerritory.getPlaceUnits().addAll(unitsToPlace);
			
			// Purchase long range attack units for any remaining production
			int remainingUnitProduction = purchaseTerritories.get(t).getRemainingUnitProduction();
			while (true)
			{
				// Find current purchase option
				final ProPurchaseOption ppo = bestAttackOption;
				if (remainingUnitProduction >= ppo.getQuantity() && bestAttackOption.getCost() <= PUsRemaining)
				{
					PUsRemaining -= ppo.getCost();
					remainingUnitProduction -= ppo.getQuantity();
					final List<Unit> newUnit = ppo.getUnitType().create(ppo.getQuantity(), player, true);
					placeTerritory.getPlaceUnits().addAll(newUnit);
					LogUtils.log(Level.FINEST, t + ", addedUnit=" + newUnit);
				}
				else
				{
					break;
				}
			}
		}
		
		return PUsRemaining;
	}
	
	private IntegerMap<ProductionRule> populateProductionRuleMap(final Map<Territory, ProPurchaseTerritory> purchaseTerritories, final List<ProPurchaseOption> landPurchaseOptions,
				final List<ProPurchaseOption> airPurchaseOptions, final List<ProPurchaseOption> seaPurchaseOptions, final List<ProPurchaseOption> factoryPurchaseOptions)
	{
		LogUtils.log(Level.FINE, "Populate production rule map");
		
		final List<ProPurchaseOption> purchaseOptions = new ArrayList<ProPurchaseOption>(landPurchaseOptions);
		purchaseOptions.addAll(airPurchaseOptions);
		purchaseOptions.addAll(seaPurchaseOptions);
		purchaseOptions.addAll(factoryPurchaseOptions);
		final IntegerMap<ProductionRule> purchaseMap = new IntegerMap<ProductionRule>();
		for (final ProPurchaseOption ppo : purchaseOptions)
		{
			int numUnits = 0;
			for (final Territory t : purchaseTerritories.keySet())
			{
				for (final ProPlaceTerritory ppt : purchaseTerritories.get(t).getCanPlaceTerritories())
				{
					for (final Unit u : ppt.getPlaceUnits())
					{
						if (u.getUnitType().equals(ppo.getUnitType()))
							numUnits++;
					}
				}
			}
			if (numUnits > 0)
			{
				final int numProductionRule = numUnits / ppo.getQuantity();
				purchaseMap.put(ppo.getProductionRule(), numProductionRule);
				LogUtils.log(Level.FINE, numProductionRule + " " + ppo.getProductionRule());
			}
		}
		return purchaseMap;
	}
	
	private void doPlace(final Territory where, final Collection<Unit> toPlace, final IAbstractPlaceDelegate del)
	{
		final String message = del.placeUnits(new ArrayList<Unit>(toPlace), where);
		if (message != null)
		{
			LogUtils.log(Level.WARNING, message);
			LogUtils.log(Level.WARNING, "Attempt was at: " + where + " with: " + toPlace);
		}
		utils.pause();
	}
	
}