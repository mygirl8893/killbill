/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014-2015 Groupon, Inc
 * Copyright 2014-2015 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
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

package org.killbill.billing.invoice.notification;

import java.io.IOException;
import java.util.UUID;

import org.joda.time.DateTime;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.invoice.api.DefaultInvoiceService;
import org.killbill.billing.util.entity.dao.EntitySqlDao;
import org.killbill.billing.util.entity.dao.EntitySqlDaoWrapperFactory;
import org.killbill.notificationq.api.NotificationQueue;
import org.killbill.notificationq.api.NotificationQueueService;
import org.killbill.notificationq.api.NotificationQueueService.NoSuchNotificationQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

public class DefaultNextBillingDatePoster implements NextBillingDatePoster {

    private static final Logger log = LoggerFactory.getLogger(DefaultNextBillingDatePoster.class);

    private final NotificationQueueService notificationQueueService;

    @Inject
    public DefaultNextBillingDatePoster(final NotificationQueueService notificationQueueService) {
        this.notificationQueueService = notificationQueueService;
    }

    @Override
    public void insertNextBillingNotificationFromTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory, final UUID accountId,
                                                             final UUID subscriptionId, final DateTime futureNotificationTime, final InternalCallContext internalCallContext) {
        final NotificationQueue nextBillingQueue;
        try {
            nextBillingQueue = notificationQueueService.getNotificationQueue(DefaultInvoiceService.INVOICE_SERVICE_NAME,
                                                                             DefaultNextBillingDateNotifier.NEXT_BILLING_DATE_NOTIFIER_QUEUE);
            log.info("Queuing next billing date notification at {} for subscriptionId {}", futureNotificationTime.toString(), subscriptionId.toString());

            nextBillingQueue.recordFutureNotificationFromTransaction(entitySqlDaoWrapperFactory.getSqlDao(), futureNotificationTime,
                                                                     new NextBillingDateNotificationKey(subscriptionId), internalCallContext.getUserToken(),
                                                                     internalCallContext.getAccountRecordId(), internalCallContext.getTenantRecordId());
        } catch (final NoSuchNotificationQueue e) {
            log.error("Attempting to put items on a non-existent queue (NextBillingDateNotifier).", e);
        } catch (final IOException e) {
            log.error("Failed to serialize notificationKey for subscriptionId {}", subscriptionId);
        }
    }
}
