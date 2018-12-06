package org.eevolution.api.impl;

import static org.adempiere.model.InterfaceWrapperHelper.saveRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.ad.dao.IQueryBuilder;
import org.adempiere.mm.attributes.AttributeSetInstanceId;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.service.ClientId;
import org.adempiere.service.OrgId;
import org.eevolution.api.IPPOrderCostDAO;
import org.eevolution.api.PPOrderCost;
import org.eevolution.api.PPOrderCosts;
import org.eevolution.model.I_PP_Order_Cost;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import de.metas.acct.api.AcctSchema;
import de.metas.acct.api.AcctSchemaId;
import de.metas.acct.api.IAcctSchemaDAO;
import de.metas.costing.CostAmount;
import de.metas.costing.CostElementId;
import de.metas.costing.CostPrice;
import de.metas.costing.CostSegmentAndElement;
import de.metas.costing.IProductCostingBL;
import de.metas.material.planning.pporder.PPOrderId;
import de.metas.product.ProductId;
import de.metas.util.GuavaCollectors;
import de.metas.util.Services;
import lombok.NonNull;

public class PPOrderCostDAO implements IPPOrderCostDAO
{
	@Override
	public PPOrderCosts getByOrderId(@NonNull final PPOrderId orderId)
	{
		final ImmutableList<PPOrderCost> costs = retrieveAllOrderCostsQuery(orderId)
				.addOnlyActiveRecordsFilter()
				.create()
				.stream()
				.map(this::toPPOrderCost)
				.collect(ImmutableList.toImmutableList());

		return PPOrderCosts.builder()
				.orderId(orderId)
				.costs(costs)
				.build();
	}

	@Override
	public void deleteByOrderId(@NonNull final PPOrderId ppOrderId)
	{
		retrieveAllOrderCostsQuery(ppOrderId)
				.create()
				.delete();
	}

	private IQueryBuilder<I_PP_Order_Cost> retrieveAllOrderCostsQuery(@NonNull final PPOrderId ppOrderId)
	{
		return Services.get(IQueryBL.class)
				.createQueryBuilder(I_PP_Order_Cost.class)
				.addEqualsFilter(I_PP_Order_Cost.COLUMNNAME_PP_Order_ID, ppOrderId)
		// .addOnlyActiveRecordsFilter() // NOTE: we need to retrieve ALL costs
		;
	}

	@Override
	public void save(@NonNull final PPOrderCosts orderCosts)
	{
		final PPOrderId orderId = orderCosts.getOrderId();

		final HashMap<Integer, I_PP_Order_Cost> existingRecordsById = retrieveAllOrderCostsQuery(orderId)
				.create()
				.stream()
				.collect(GuavaCollectors.toHashMapByKey(I_PP_Order_Cost::getPP_Order_Cost_ID));

		//
		// Delete old records which are no longer needed
		final Set<Integer> repoIdsToUpdate = orderCosts.toList().stream().map(PPOrderCost::getRepoId).collect(ImmutableSet.toImmutableSet());
		final List<I_PP_Order_Cost> recordsToDelete = new ArrayList<>();
		for (final int repoId : ImmutableSet.copyOf(existingRecordsById.keySet()))
		{
			if (repoIdsToUpdate.contains(repoId))
			{
				continue;
			}

			recordsToDelete.add(existingRecordsById.remove(repoId));
		}
		InterfaceWrapperHelper.deleteAll(recordsToDelete);

		//
		// Create/Update the remaining records
		orderCosts.forEach(cost -> savePPOrderCost(cost, orderId, existingRecordsById.remove(cost.getRepoId())));
	}

	private void savePPOrderCost(
			@NonNull final PPOrderCost cost,
			@NonNull final PPOrderId orderId,
			@Nullable final I_PP_Order_Cost existingRecord)
	{
		final I_PP_Order_Cost record;
		if (existingRecord == null)
		{
			record = InterfaceWrapperHelper.newInstance(I_PP_Order_Cost.class);
			record.setPP_Order_ID(orderId.getRepoId());
		}
		else
		{
			record = existingRecord;
		}

		updateRecord(record, cost);

		saveRecord(record);
		cost.setRepoId(record.getPP_Order_Cost_ID());
	}

	private void updateRecord(@NonNull final I_PP_Order_Cost record, @NonNull final PPOrderCost from)
	{
		record.setIsActive(true);

		final CostSegmentAndElement costSegmentAndElement = from.getCostSegmentAndElement();
		record.setAD_Org_ID(costSegmentAndElement.getOrgId().getRepoId());
		record.setC_AcctSchema_ID(costSegmentAndElement.getAcctSchemaId().getRepoId());
		record.setM_CostType_ID(costSegmentAndElement.getCostTypeId().getRepoId());
		record.setM_Product_ID(costSegmentAndElement.getProductId().getRepoId());
		record.setM_AttributeSetInstance_ID(costSegmentAndElement.getAttributeSetInstanceId().getRepoId());
		record.setM_CostElement_ID(costSegmentAndElement.getCostElementId().getRepoId());

		final CostPrice price = from.getPrice();
		record.setCurrentCostPrice(price.getOwnCostPrice().getValue());
		record.setCurrentCostPriceLL(price.getComponentsCostPrice().getValue());
		// ppOrderCost.setCumulatedAmt(cost.getCumulatedAmt()); // TODO: delete it
		// ppOrderCost.setCumulatedQty(cost.getCumulatedQty()); // TODO: delete it
	}

	private PPOrderCost toPPOrderCost(final I_PP_Order_Cost record)
	{
		final IProductCostingBL productCostingBL = Services.get(IProductCostingBL.class);
		final IAcctSchemaDAO acctSchemasRepo = Services.get(IAcctSchemaDAO.class);

		final AcctSchema acctSchema = acctSchemasRepo.getById(AcctSchemaId.ofRepoId(record.getC_AcctSchema_ID()));
		final ProductId productId = ProductId.ofRepoId(record.getM_Product_ID());

		final CostSegmentAndElement costSegmentAndElement = CostSegmentAndElement.builder()
				.costingLevel(productCostingBL.getCostingLevel(productId, acctSchema))
				.acctSchemaId(acctSchema.getId())
				.costTypeId(acctSchema.getCosting().getCostTypeId())
				.clientId(ClientId.ofRepoId(record.getAD_Client_ID()))
				.orgId(OrgId.ofRepoId(record.getAD_Org_ID()))
				.productId(productId)
				.attributeSetInstanceId(AttributeSetInstanceId.ofRepoIdOrNone(record.getM_AttributeSetInstance_ID()))
				.costElementId(CostElementId.ofRepoId(record.getM_CostElement_ID()))
				.build();

		return PPOrderCost.builder()
				.repoId(record.getPP_Order_Cost_ID())
				.costSegmentAndElement(costSegmentAndElement)
				.price(CostPrice.builder()
						.ownCostPrice(CostAmount.of(record.getCurrentCostPrice(), acctSchema.getCurrencyId()))
						.componentsCostPrice(CostAmount.of(record.getCurrentCostPriceLL(), acctSchema.getCurrencyId()))
						.build())
				.build();
	}
}
