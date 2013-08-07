/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.invoice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.billing.ErrorCode;
import com.ning.billing.account.api.Account;
import com.ning.billing.account.api.AccountApiException;
import com.ning.billing.bus.api.PersistentBus;
import com.ning.billing.bus.api.PersistentBus.EventBusException;
import com.ning.billing.catalog.api.Currency;
import com.ning.billing.subscription.api.user.SubscriptionUserApiException;
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.invoice.api.InvoiceApiException;
import com.ning.billing.invoice.api.InvoiceItem;
import com.ning.billing.invoice.api.InvoiceItemType;
import com.ning.billing.invoice.api.InvoiceNotifier;
import com.ning.billing.invoice.api.InvoicePayment;
import com.ning.billing.invoice.api.user.DefaultInvoiceAdjustmentEvent;
import com.ning.billing.invoice.api.user.DefaultInvoiceCreationEvent;
import com.ning.billing.invoice.api.user.DefaultNullInvoiceEvent;
import com.ning.billing.invoice.dao.InvoiceDao;
import com.ning.billing.invoice.dao.InvoiceItemModelDao;
import com.ning.billing.invoice.dao.InvoiceModelDao;
import com.ning.billing.invoice.dao.InvoicePaymentModelDao;
import com.ning.billing.invoice.generator.InvoiceGenerator;
import com.ning.billing.invoice.model.DefaultInvoice;
import com.ning.billing.invoice.model.FixedPriceInvoiceItem;
import com.ning.billing.invoice.model.RecurringInvoiceItem;
import com.ning.billing.util.callcontext.InternalCallContext;
import com.ning.billing.clock.Clock;
import com.ning.billing.util.events.BusInternalEvent;
import com.ning.billing.util.events.EffectiveSubscriptionInternalEvent;
import com.ning.billing.util.events.InvoiceAdjustmentInternalEvent;
import com.ning.billing.util.events.InvoiceInternalEvent;
import com.ning.billing.util.globallocker.GlobalLock;
import com.ning.billing.util.globallocker.GlobalLocker;
import com.ning.billing.util.globallocker.GlobalLocker.LockerType;
import com.ning.billing.util.globallocker.LockFailedException;
import com.ning.billing.util.svcapi.account.AccountInternalApi;
import com.ning.billing.util.svcapi.subscription.SubscriptionInternalApi;
import com.ning.billing.util.svcapi.junction.BillingEventSet;
import com.ning.billing.util.svcapi.junction.BillingInternalApi;
import com.ning.billing.util.timezone.DateAndTimeZoneContext;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;

public class InvoiceDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InvoiceDispatcher.class);
    private static final int NB_LOCK_TRY = 5;

    private final InvoiceGenerator generator;
    private final BillingInternalApi billingApi;
    private final AccountInternalApi accountApi;
    private final SubscriptionInternalApi subscriptionApi;
    private final InvoiceDao invoiceDao;
    private final InvoiceNotifier invoiceNotifier;
    private final GlobalLocker locker;
    private final PersistentBus eventBus;
    private final Clock clock;

    @Inject
    public InvoiceDispatcher(final InvoiceGenerator generator, final AccountInternalApi accountApi,
                             final BillingInternalApi billingApi,
                             final SubscriptionInternalApi SubscriptionApi,
                             final InvoiceDao invoiceDao,
                             final InvoiceNotifier invoiceNotifier,
                             final GlobalLocker locker,
                             final PersistentBus eventBus,
                             final Clock clock) {
        this.generator = generator;
        this.billingApi = billingApi;
        this.subscriptionApi = SubscriptionApi;
        this.accountApi = accountApi;
        this.invoiceDao = invoiceDao;
        this.invoiceNotifier = invoiceNotifier;
        this.locker = locker;
        this.eventBus = eventBus;
        this.clock = clock;
    }

    public void processSubscription(final EffectiveSubscriptionInternalEvent transition,
                                    final InternalCallContext context) throws InvoiceApiException {
        final UUID subscriptionId = transition.getSubscriptionId();
        final DateTime targetDate = transition.getEffectiveTransitionTime();
        processSubscription(subscriptionId, targetDate, context);
    }

    public void processSubscription(final UUID subscriptionId, final DateTime targetDate, final InternalCallContext context) throws InvoiceApiException {
        try {
            if (subscriptionId == null) {
                log.error("Failed handling SubscriptionBase change.", new InvoiceApiException(ErrorCode.INVOICE_INVALID_TRANSITION));
                return;
            }
            final UUID accountId = subscriptionApi.getAccountIdFromSubscriptionId(subscriptionId, context);
            processAccount(accountId, targetDate, false, context);
        } catch (SubscriptionUserApiException e) {
            log.error("Failed handling SubscriptionBase change.",
                      new InvoiceApiException(ErrorCode.INVOICE_NO_ACCOUNT_ID_FOR_SUBSCRIPTION_ID, subscriptionId.toString()));
        }
    }

    public Invoice processAccount(final UUID accountId, final DateTime targetDate,
                                  final boolean dryRun, final InternalCallContext context) throws InvoiceApiException {
        GlobalLock lock = null;
        try {
            lock = locker.lockWithNumberOfTries(LockerType.ACCOUNT_FOR_INVOICE_PAYMENTS, accountId.toString(), NB_LOCK_TRY);

            return processAccountWithLock(accountId, targetDate, dryRun, context);
        } catch (LockFailedException e) {
            // Not good!
            log.error(String.format("Failed to process invoice for account %s, targetDate %s",
                                    accountId.toString(), targetDate), e);
        } finally {
            if (lock != null) {
                lock.release();
            }
        }
        return null;
    }


    private Invoice processAccountWithLock(final UUID accountId, final DateTime targetDateTime,
                                           final boolean dryRun, final InternalCallContext context) throws InvoiceApiException {
        try {

            // Make sure to first set the BCD if needed then get the account object (to have the BCD set)
            final BillingEventSet billingEvents = billingApi.getBillingEventsForAccountAndUpdateAccountBCD(accountId, context);
            final Account account = accountApi.getAccountById(accountId, context);

            final DateAndTimeZoneContext dateAndTimeZoneContext = billingEvents.iterator().hasNext() ?
                                                                  new DateAndTimeZoneContext(billingEvents.iterator().next().getEffectiveDate(), account.getTimeZone(), clock) :
                                                                  new DateAndTimeZoneContext(null, account.getTimeZone(), clock);


            List<Invoice> invoices = new ArrayList<Invoice>();
            if (!billingEvents.isAccountAutoInvoiceOff()) {
                invoices = ImmutableList.<Invoice>copyOf(Collections2.transform(invoiceDao.getInvoicesByAccount(accountId, context),
                                                                                new Function<InvoiceModelDao, Invoice>() {
                                                                                    @Override
                                                                                    public Invoice apply(final InvoiceModelDao input) {
                                                                                        return new DefaultInvoice(input);
                                                                                    }
                                                                                })); //no need to fetch, invoicing is off on this account
            }

            final Currency targetCurrency = account.getCurrency();

            final LocalDate targetDate = dateAndTimeZoneContext.computeTargetDate(targetDateTime);
            final Invoice invoice = generator.generateInvoice(accountId, billingEvents, invoices, targetDate, targetCurrency);
            if (invoice == null) {
                log.info("Generated null invoice for accountId {} and targetDate {} (targetDateTime {})", new Object[]{accountId, targetDate, targetDateTime});
                if (!dryRun) {
                    final BusInternalEvent event = new DefaultNullInvoiceEvent(accountId, clock.getUTCToday(),
                                                                               context.getAccountRecordId(), context.getTenantRecordId(), context.getUserToken());
                    postEvent(event, accountId, context);
                }
            } else {
                log.info("Generated invoice {} with {} items for accountId {} and targetDate {} (targetDateTime {})", new Object[]{invoice.getId(), invoice.getNumberOfItems(),
                        accountId, targetDate, targetDateTime});
                if (!dryRun) {

                    // Extract the set of invoiceId for which we see items that don't belong to current generated invoice
                    final Set<UUID> adjustedUniqueOtherInvoiceId = new TreeSet<UUID>();
                    adjustedUniqueOtherInvoiceId.addAll(Collections2.transform(invoice.getInvoiceItems(), new Function<InvoiceItem, UUID>() {
                        @Nullable
                        @Override
                        public UUID apply(@Nullable final InvoiceItem input) {
                            return input.getInvoiceId();
                        }
                    }));
                    final boolean isRealInvoiceWithItems = adjustedUniqueOtherInvoiceId.remove(invoice.getId());


                    final InvoiceModelDao invoiceModelDao = new InvoiceModelDao(invoice);
                    final List<InvoiceItemModelDao> invoiceItemModelDaos = ImmutableList.<InvoiceItemModelDao>copyOf(Collections2.transform(invoice.getInvoiceItems(),
                                                                                                                                            new Function<InvoiceItem, InvoiceItemModelDao>() {
                                                                                                                                                @Override
                                                                                                                                                public InvoiceItemModelDao apply(final InvoiceItem input) {
                                                                                                                                                    return new InvoiceItemModelDao(input);
                                                                                                                                                }
                                                                                                                                            }));
                    // Not really needed, there shouldn't be any payment at this stage
                    final List<InvoicePaymentModelDao> invoicePaymentModelDaos = ImmutableList.<InvoicePaymentModelDao>copyOf(Collections2.transform(invoice.getPayments(),
                                                                                                                                                     new Function<InvoicePayment, InvoicePaymentModelDao>() {
                                                                                                                                                         @Override
                                                                                                                                                         public InvoicePaymentModelDao apply(final InvoicePayment input) {
                                                                                                                                                             return new InvoicePaymentModelDao(input);
                                                                                                                                                         }
                                                                                                                                                     }));

                    final Map<UUID, DateTime> callbackDateTimePerSubscriptions = createNextFutureNotificationDate(invoiceItemModelDaos, dateAndTimeZoneContext);
                    invoiceDao.createInvoice(invoiceModelDao, invoiceItemModelDaos, invoicePaymentModelDaos, isRealInvoiceWithItems, callbackDateTimePerSubscriptions, context);

                    final List<InvoiceItem> fixedPriceInvoiceItems = invoice.getInvoiceItems(FixedPriceInvoiceItem.class);
                    final List<InvoiceItem> recurringInvoiceItems = invoice.getInvoiceItems(RecurringInvoiceItem.class);
                    setChargedThroughDates(dateAndTimeZoneContext, fixedPriceInvoiceItems, recurringInvoiceItems, context);


                    final List<InvoiceInternalEvent> events = new ArrayList<InvoiceInternalEvent>();
                    if (isRealInvoiceWithItems) {
                        events.add(new DefaultInvoiceCreationEvent(invoice.getId(), invoice.getAccountId(),
                                                                   invoice.getBalance(), invoice.getCurrency(),
                                                                   context.getAccountRecordId(), context.getTenantRecordId(), context.getUserToken()));
                    }
                    for (UUID cur : adjustedUniqueOtherInvoiceId) {
                        final InvoiceAdjustmentInternalEvent event = new DefaultInvoiceAdjustmentEvent(cur, invoice.getAccountId(),
                                                                                                       context.getAccountRecordId(), context.getTenantRecordId(), context.getUserToken());
                        events.add(event);
                    }


                    for (InvoiceInternalEvent event : events) {
                        postEvent(event, accountId, context);
                    }
                }
            }

            if (account.isNotifiedForInvoices() && invoice != null && !dryRun) {
                // Need to re-hydrate the invoice object to get the invoice number (record id)
                // API_FIX InvoiceNotifier public API?
                invoiceNotifier.notify(account, new DefaultInvoice(invoiceDao.getById(invoice.getId(), context)), context.toTenantContext());
            }

            return invoice;
        } catch (AccountApiException e) {
            log.error("Failed handling SubscriptionBase change.", e);
            return null;
        }
    }


    @VisibleForTesting
    Map<UUID, DateTime> createNextFutureNotificationDate(final List<InvoiceItemModelDao> invoiceItems, final DateAndTimeZoneContext dateAndTimeZoneContext) {
        final Map<UUID, DateTime> result = new HashMap<UUID, DateTime>();

        // For each subscription that has a positive (amount) recurring item, create the date
        // at which we should be called back for next invoice.
        //
        for (final InvoiceItemModelDao item : invoiceItems) {
            if (item.getType() == InvoiceItemType.RECURRING) {
                if ((item.getEndDate() != null) &&
                    (item.getAmount() == null ||
                     item.getAmount().compareTo(BigDecimal.ZERO) >= 0)) {
                    result.put(item.getSubscriptionId(), dateAndTimeZoneContext.computeUTCDateTimeFromLocalDate(item.getEndDate()));
                }
            }
        }
        return result;
    }

    private void setChargedThroughDates(final DateAndTimeZoneContext dateAndTimeZoneContext,
                                        final Collection<InvoiceItem> fixedPriceItems,
                                        final Collection<InvoiceItem> recurringItems,
                                        final InternalCallContext context) {
        final Map<UUID, DateTime> chargeThroughDates = new HashMap<UUID, DateTime>();
        addInvoiceItemsToChargeThroughDates(dateAndTimeZoneContext, chargeThroughDates, fixedPriceItems);
        addInvoiceItemsToChargeThroughDates(dateAndTimeZoneContext, chargeThroughDates, recurringItems);

        for (final UUID subscriptionId : chargeThroughDates.keySet()) {
            if (subscriptionId != null) {
                final DateTime chargeThroughDate = chargeThroughDates.get(subscriptionId);
                subscriptionApi.setChargedThroughDate(subscriptionId, chargeThroughDate, context);
            }
        }
    }

    private void postEvent(final BusInternalEvent event, final UUID accountId, final InternalCallContext context) {
        try {
            eventBus.post(event);
        } catch (EventBusException e) {
            log.error(String.format("Failed to post event %s for account %s", event.getBusEventType(), accountId), e);
        }
    }

    private void addInvoiceItemsToChargeThroughDates(final DateAndTimeZoneContext dateAndTimeZoneContext,
                                                     final Map<UUID, DateTime> chargeThroughDates,
                                                     final Collection<InvoiceItem> items) {

        for (final InvoiceItem item : items) {
            final UUID subscriptionId = item.getSubscriptionId();
            final LocalDate endDate = (item.getEndDate() != null) ? item.getEndDate() : item.getStartDate();

            final DateTime proposedChargedThroughDate = dateAndTimeZoneContext.computeUTCDateTimeFromLocalDate(endDate);
            if (chargeThroughDates.containsKey(subscriptionId)) {
                if (chargeThroughDates.get(subscriptionId).isBefore(proposedChargedThroughDate)) {
                    chargeThroughDates.put(subscriptionId, proposedChargedThroughDate);
                }
            } else {
                chargeThroughDates.put(subscriptionId, proposedChargedThroughDate);
            }
        }
    }

}
