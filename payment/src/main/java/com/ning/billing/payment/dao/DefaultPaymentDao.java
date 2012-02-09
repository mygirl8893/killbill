/*
 * Copyright 2010-2011 Ning, Inc.
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

package com.ning.billing.payment.dao;

import java.util.UUID;

import org.skife.jdbi.v2.IDBI;

import com.google.inject.Inject;
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.payment.api.PaymentAttempt;
import com.ning.billing.payment.api.PaymentInfo;

public class DefaultPaymentDao implements PaymentDao {
    private final PaymentSqlDao sqlDao;

    @Inject
    public DefaultPaymentDao(IDBI dbi) {
        this.sqlDao = dbi.onDemand(PaymentSqlDao.class);
    }

    @Override
    public PaymentAttempt getPaymentAttemptForPaymentId(String paymentId) {
        return sqlDao.getPaymentAttemptForPaymentId(paymentId);
    }

    @Override
    public PaymentAttempt getPaymentAttemptForInvoiceId(String invoiceId) {
        return sqlDao.getPaymentAttemptForInvoiceId(invoiceId);
    }

    @Override
    public PaymentAttempt createPaymentAttempt(Invoice invoice) {
        final PaymentAttempt paymentAttempt = new PaymentAttempt(UUID.randomUUID(), invoice);

        sqlDao.insertPaymentAttempt(paymentAttempt);
        return paymentAttempt;
    }

    @Override
    public void savePaymentInfo(PaymentInfo info) {
        sqlDao.insertPaymentInfo(info);
    }

    @Override
    public void updatePaymentAttemptWithPaymentId(UUID paymentAttemptId, String paymentId) {
        sqlDao.updatePaymentAttemptWithPaymentId(paymentAttemptId.toString(), paymentId);
    }

    @Override
    public void updatePaymentInfo(String type, String paymentId, String cardType, String cardCountry) {
        sqlDao.updatePaymentInfo(type, paymentId, cardType, cardCountry);
    }

    @Override
    public PaymentAttempt getPaymentAttemptById(UUID paymentAttemptId) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void updatePaymentAttempt(PaymentAttempt updatedPaymentAttempt) {
        // TODO Auto-generated method stub

    }

}
