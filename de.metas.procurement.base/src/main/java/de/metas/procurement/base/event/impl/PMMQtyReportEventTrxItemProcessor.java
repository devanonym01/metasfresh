package de.metas.procurement.base.event.impl;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.ad.trx.processor.spi.TrxItemProcessorAdapter;
import org.adempiere.bpartner.service.IBPartnerDAO;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.pricing.api.IEditablePricingContext;
import org.adempiere.pricing.api.IPricingBL;
import org.adempiere.pricing.api.IPricingResult;
import org.adempiere.uom.api.IUOMConversionBL;
import org.adempiere.util.Check;
import org.adempiere.util.ILoggable;
import org.adempiere.util.Services;
import org.compiere.model.I_C_BPartner;
import org.compiere.model.I_C_UOM;
import org.compiere.model.I_M_Product;
import org.compiere.model.I_M_ProductPrice;

import com.google.common.annotations.VisibleForTesting;

import de.metas.adempiere.model.I_C_BPartner_Location;
import de.metas.flatrate.model.I_C_Flatrate_Term;
import de.metas.lock.api.ILockManager;
import de.metas.procurement.base.IPMMContractsDAO;
import de.metas.procurement.base.balance.IPMMBalanceChangeEventProcessor;
import de.metas.procurement.base.balance.PMMBalanceChangeEvent;
import de.metas.procurement.base.model.I_C_Flatrate_DataEntry;
import de.metas.procurement.base.model.I_PMM_PurchaseCandidate;
import de.metas.procurement.base.model.I_PMM_QtyReport_Event;
import de.metas.procurement.base.order.IPMMPurchaseCandidateBL;
import de.metas.procurement.base.order.IPMMPurchaseCandidateDAO;

/*
 * #%L
 * de.metas.procurement.base
 * %%
 * Copyright (C) 2016 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

/**
 * Processes {@link I_PMM_QtyReport_Event}s and creates/updates {@link I_PMM_PurchaseCandidate}s.
 *
 * @author metas-dev <dev@metas-fresh.com>
 *
 */
class PMMQtyReportEventTrxItemProcessor extends TrxItemProcessorAdapter<I_PMM_QtyReport_Event, Void>
{
	// services
	private final transient ILockManager lockManager = Services.get(ILockManager.class);
	private final transient IPMMPurchaseCandidateDAO purchaseCandidateDAO = Services.get(IPMMPurchaseCandidateDAO.class);
	private final transient IPMMPurchaseCandidateBL purchaseCandidateBL = Services.get(IPMMPurchaseCandidateBL.class);
	private final transient IPMMBalanceChangeEventProcessor pmmBalanceEventProcessor = Services.get(IPMMBalanceChangeEventProcessor.class);
	private final transient IBPartnerDAO bpartnerDAO = Services.get(IBPartnerDAO.class);
	private final transient IPricingBL pricingBL = Services.get(IPricingBL.class);
	private final transient IPMMContractsDAO pmmContractsDAO = Services.get(IPMMContractsDAO.class);
	private final transient IUOMConversionBL uomConversionBL = Services.get(IUOMConversionBL.class);

	private final AtomicInteger countProcessed = new AtomicInteger(0);
	private final AtomicInteger countErrors = new AtomicInteger(0);
	private final AtomicInteger countSkipped = new AtomicInteger(0);

	/**
	 * Creates/Updates a {@link I_PMM_PurchaseCandidate} for the given <code>event</code> <b>and also</b> updates the given <code>event</code>'s pricing.
	 */
	@Override
	public void process(final I_PMM_QtyReport_Event event)
	{
		//
		// Get candidate
		I_PMM_PurchaseCandidate candidate = purchaseCandidateDAO.retrieveFor(event.getC_BPartner_ID(), event.getM_Product_ID(), event.getDatePromised());

		//
		// If candidate is currently locked, skip processing this event for now
		if (candidate != null && lockManager.isLocked(candidate))
		{
			final String errorMsg = "Skip processing event because candidate is currently locked: " + candidate;
			markSkipped(event, candidate, errorMsg);
			return;
		}

		if (candidate != null && isProcessedByFutureEvent(candidate, event))
		{
			final String errorMsg = "Skipped because candidate " + candidate + " was already processed by future event: " + candidate.getLast_QtyReport_Event_ID();
			markProcessed(event, candidate, errorMsg);
			return;
		}

		try
		{
			//
			// If no candidate found, create a new candidate
			if (candidate == null)
			{
				candidate = InterfaceWrapperHelper.newInstance(I_PMM_PurchaseCandidate.class);
				candidate.setC_BPartner_ID(event.getC_BPartner_ID());
				candidate.setM_Product_ID(event.getM_Product_ID());
				candidate.setC_UOM_ID(event.getC_UOM_ID());
				candidate.setM_HU_PI_Item_Product_ID(event.getM_HU_PI_Item_Product_ID());
				candidate.setDatePromised(event.getDatePromised());

				candidate.setAD_Org_ID(event.getAD_Org_ID());
				if (event.getM_Warehouse_ID() > 0)
				{
					candidate.setM_Warehouse_ID(event.getM_Warehouse_ID());
				}

				//
				// Pricing
				updatePricing(event);
				candidate.setM_PricingSystem_ID(event.getM_PricingSystem_ID());
				candidate.setM_PriceList_ID(event.getM_PriceList_ID());
				candidate.setC_Currency_ID(event.getC_Currency_ID());
				if (event.getC_Flatrate_DataEntry_ID() > 0)
				{
					candidate.setC_Flatrate_DataEntry_ID(event.getC_Flatrate_DataEntry_ID());
				}
				candidate.setPrice(event.getPrice());
			}

			//
			// Update event and capture the old candidate values
			// NOTE: these fields are critical for PMM_Balance
			final BigDecimal candidate_QtyPromised = candidate.getQtyPromised();
			final BigDecimal candidate_QtyPromisedTU = candidate.getQtyPromised_TU();
			event.setQtyPromised_Old(candidate_QtyPromised);
			event.setQtyPromised_TU_Old(candidate_QtyPromisedTU);

			//
			// Update the candidate with the values from event
			purchaseCandidateBL.setQtyPromisedAndUpdate(candidate, event.getQtyPromised(), event.getQtyPromised_TU());
			InterfaceWrapperHelper.save(candidate);

			//
			// Update PMM_Balance
			pmmBalanceEventProcessor.addEvent(createPMMBalanceChangeEvent(event));

			//
			// Mark the event as processed
			markProcessed(event, candidate);
		}
		catch (final Exception e)
		{
			markError(event, e);
		}
	}

	private final void updatePricing(final I_PMM_QtyReport_Event qtyReportEvent)
	{
		// Get BPartner
		final int bpartnerId = qtyReportEvent.getC_BPartner_ID();
		if (bpartnerId <= 0)
		{
			throw new AdempiereException("@Missing@ @" + I_PMM_QtyReport_Event.COLUMNNAME_C_BPartner_ID + "@");
		}

		// Get Product and UOM
		final I_M_Product product = qtyReportEvent.getM_Product();
		if (product == null)
		{
			throw new AdempiereException("@Missing@ @" + I_PMM_QtyReport_Event.COLUMNNAME_M_Product_ID + "@");
		}
		final I_C_UOM uom = qtyReportEvent.getC_UOM();
		if (uom == null)
		{
			throw new AdempiereException("@Missing@ @" + I_PMM_QtyReport_Event.COLUMNNAME_C_UOM_ID + "@");
		}

		// Always get the pricing from masterdata.
		// We need it to be there, e.g. to know if the price is incl VAT and which VAT category to use.
		updatePriceFromPricingMasterdata(qtyReportEvent);

		//
		// contract product: override the price amount and currency from the contract, if one is set there
		final String contractLine_uuid = qtyReportEvent.getContractLine_UUID();
		if (!Check.isEmpty(contractLine_uuid, true))
		{
			updatePriceFromContract(qtyReportEvent);
		}
	}

	private void updatePriceFromContract(final I_PMM_QtyReport_Event qtyReportEvent)
	{
		final I_C_Flatrate_Term flatrateTerm = qtyReportEvent.getC_Flatrate_Term();
		if (flatrateTerm == null)
		{
			// we are called, because qtyReportEvent has a contractLine_uuid. So if there is no term then something is wrong
			throw new AdempiereException("@Missing@ @" + I_C_Flatrate_Term.COLUMNNAME_C_Flatrate_Term_ID + "@");
		}

		final I_M_Product product = qtyReportEvent.getM_Product();
		final I_C_UOM uom = qtyReportEvent.getC_UOM();
		final Timestamp datePromised = qtyReportEvent.getDatePromised();

		final I_C_Flatrate_DataEntry dataEntryForProduct = pmmContractsDAO.retrieveFlatrateDataEntry(flatrateTerm, datePromised);
		if (dataEntryForProduct == null
				|| dataEntryForProduct.getFlatrateAmtPerUOM() == null
				|| dataEntryForProduct.getFlatrateAmtPerUOM().signum() <= 0)
		{
			return; // nothing to do
		}

		final BigDecimal price = uomConversionBL.convertPrice(
				product,
				dataEntryForProduct.getFlatrateAmtPerUOM(),
				flatrateTerm.getC_UOM(),  								// this is the flatrateAmt's UOM
				uom,  													// this is the qtyReportEvent's UOM
				flatrateTerm.getC_Currency().getStdPrecision());

		qtyReportEvent.setC_Flatrate_Term(flatrateTerm);
		qtyReportEvent.setC_Flatrate_DataEntry(dataEntryForProduct);
		qtyReportEvent.setC_Currency_ID(flatrateTerm.getC_Currency_ID());
		qtyReportEvent.setPrice(price);
	}

	private void updatePriceFromPricingMasterdata(final I_PMM_QtyReport_Event qtyReportEvent)
	{
		final boolean soTrx = false;
		final Properties ctx = InterfaceWrapperHelper.getCtx(qtyReportEvent);

		final int bpartnerId = qtyReportEvent.getC_BPartner_ID();
		final I_M_Product product = qtyReportEvent.getM_Product();
		final I_C_UOM uom = qtyReportEvent.getC_UOM();
		final Timestamp datePromised = qtyReportEvent.getDatePromised();

		// Pricing system
		final int pricingSystemId = bpartnerDAO.retrievePricingSystemId(ctx, bpartnerId, soTrx, ITrx.TRXNAME_ThreadInherited);
		if (pricingSystemId <= 0)
		{
			// no term and no pricing system means that we can't figure out the price
			throw new AdempiereException("@Missing@ @" + I_PMM_QtyReport_Event.COLUMNNAME_M_PricingSystem_ID + "@ ("
					+ "@C_BPartner_ID@:" + bpartnerId
					+ ", @IsSOTrx@:" + soTrx
					+ ")");
		}

		// BPartner location -> Country
		final I_C_BPartner bpartner = qtyReportEvent.getC_BPartner();
		final List<I_C_BPartner_Location> shipToLocations = bpartnerDAO.retrieveBPartnerShipToLocations(bpartner);
		if (shipToLocations.isEmpty())
		{
			throw new AdempiereException("@Missing@ @" + org.compiere.model.I_C_BPartner_Location.COLUMNNAME_IsShipTo + "@");
		}
		final int countryId = shipToLocations.get(0).getC_Location().getC_Country_ID();

		//
		// Fetch price from pricing engine
		final BigDecimal qtyPromised = qtyReportEvent.getQtyPromised();
		final IEditablePricingContext pricingCtx = pricingBL.createInitialContext(product.getM_Product_ID(), bpartnerId, uom.getC_UOM_ID(), qtyPromised, soTrx);
		pricingCtx.setM_PricingSystem_ID(pricingSystemId);
		pricingCtx.setPriceDate(datePromised);
		pricingCtx.setC_Country_ID(countryId);

		final IPricingResult pricingResult = pricingBL.calculatePrice(pricingCtx);
		if (!pricingResult.isCalculated())
		{
			throw new AdempiereException("@Missing@ @" + I_M_ProductPrice.COLUMNNAME_M_ProductPrice_ID + "@: " + pricingResult);
		}

		// these will be "empty" results, if the price was not calculated
		qtyReportEvent.setM_PricingSystem_ID(pricingResult.getM_PricingSystem_ID());
		qtyReportEvent.setM_PriceList_ID(pricingResult.getM_PriceList_ID());
		qtyReportEvent.setC_Currency_ID(pricingResult.getC_Currency_ID());
		qtyReportEvent.setPrice(pricingResult.getPriceStd());
	}

	private final void markProcessed(final I_PMM_QtyReport_Event event, final I_PMM_PurchaseCandidate candidate)
	{
		final String errorMsg = null; // no error message
		markProcessed(event, candidate, errorMsg);
	}

	private final void markProcessed(final I_PMM_QtyReport_Event event, final I_PMM_PurchaseCandidate candidate, final String errorMsg)
	{
		event.setPMM_PurchaseCandidate(candidate);

		event.setProcessed(true);
		event.setIsError(!Check.isEmpty(errorMsg, true));
		event.setErrorMsg(errorMsg);
		InterfaceWrapperHelper.save(event);

		countProcessed.incrementAndGet();
	}

	private final void markError(final I_PMM_QtyReport_Event event, final Throwable ex)
	{
		final String errorMsg = ex.getLocalizedMessage();

		InterfaceWrapperHelper.refresh(event, true);
		event.setIsError(true);
		event.setErrorMsg(errorMsg);
		event.setProcessed(false);
		InterfaceWrapperHelper.save(event);

		getLoggable().addLog("Event " + event + " marked as processed with warnings: " + errorMsg);

		countErrors.incrementAndGet();
	}

	private final void markSkipped(final I_PMM_QtyReport_Event event, final I_PMM_PurchaseCandidate candidate, final String errorMsg)
	{
		event.setErrorMsg(errorMsg);
		InterfaceWrapperHelper.save(event);

		if (errorMsg != null)
		{
			getLoggable().addLog("Event " + event + " skipped: " + errorMsg);
		}
		countSkipped.incrementAndGet();

	}

	private boolean isProcessedByFutureEvent(final I_PMM_PurchaseCandidate candidate, final I_PMM_QtyReport_Event currentEvent)
	{
		if (candidate == null)
		{
			return false;
		}

		final int lastQtyReportEventId = candidate.getLast_QtyReport_Event_ID();
		if (lastQtyReportEventId > currentEvent.getPMM_QtyReport_Event_ID())
		{
			return true;
		}

		return false;
	}

	private final ILoggable getLoggable()
	{
		return ILoggable.THREADLOCAL.getLoggable();
	}

	public String getProcessSummary()
	{
		return "@Processed@ #" + countProcessed.get()
				+ ", @IsError@ #" + countErrors.get()
				+ ", @Skipped@ #" + countSkipped.get();
	}

	@VisibleForTesting
	static PMMBalanceChangeEvent createPMMBalanceChangeEvent(final I_PMM_QtyReport_Event qtyReportEvent)
	{
		final BigDecimal qtyPromisedDiff = qtyReportEvent.getQtyPromised().subtract(qtyReportEvent.getQtyPromised_Old());
		final BigDecimal qtyPromisedTUDiff = qtyReportEvent.getQtyPromised_TU().subtract(qtyReportEvent.getQtyPromised_TU_Old());
		return PMMBalanceChangeEvent.builder()
				.setC_BPartner_ID(qtyReportEvent.getC_BPartner_ID())
				.setM_Product_ID(qtyReportEvent.getM_Product_ID())
				.setM_HU_PI_Item_Product_ID(qtyReportEvent.getM_HU_PI_Item_Product_ID())
				.setC_Flatrate_DataEntry_ID(qtyReportEvent.getC_Flatrate_DataEntry_ID())
				//
				.setDate(qtyReportEvent.getDatePromised())
				//
				.setQtyPromised(qtyPromisedDiff, qtyPromisedTUDiff)
				//
				.build();
	}
}
