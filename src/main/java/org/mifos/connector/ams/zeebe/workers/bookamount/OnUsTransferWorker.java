package org.mifos.connector.ams.zeebe.workers.bookamount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.mifos.connector.ams.fineract.PaymentTypeConfig;
import org.mifos.connector.ams.fineract.PaymentTypeConfigFactory;
import org.mifos.connector.ams.mapstruct.Pain001Camt053Mapper;
import org.mifos.connector.ams.zeebe.workers.utils.BatchItemBuilder;
import org.mifos.connector.ams.zeebe.workers.utils.TransactionBody;
import org.mifos.connector.ams.zeebe.workers.utils.TransactionDetails;
import org.mifos.connector.ams.zeebe.workers.utils.TransactionItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import io.camunda.zeebe.spring.client.exception.ZeebeBpmnError;
import iso.std.iso._20022.tech.json.camt_053_001.BankToCustomerStatementV08;
import iso.std.iso._20022.tech.json.pain_001_001.Pain00100110CustomerCreditTransferInitiationV10MessageSchema;

@Component
public class OnUsTransferWorker extends AbstractMoneyInOutWorker {
	
	private static final String ERROR_FAILED_CREDIT_TRANSFER = "Error_FailedCreditTransfer";

	@Autowired
	private Pain001Camt053Mapper camt053Mapper;
	
	@Value("${fineract.incoming-money-api}")
	protected String incomingMoneyApi;
	
	@Value("${fineract.auth-token}")
	private String authToken;
	
	@Autowired
    private PaymentTypeConfigFactory paymentTypeConfigFactory;
	
	private static final DateTimeFormatter PATTERN = DateTimeFormatter.ofPattern(FORMAT);

	@JobWorker
	public Map<String, Object> transferTheAmountBetweenDisposalAccounts(JobClient jobClient, 
			ActivatedJob activatedJob,
			@Variable String internalCorrelationId,
			@Variable String paymentScheme,
			@Variable String originalPain001,
			@Variable BigDecimal amount,
			@Variable Integer creditorDisposalAccountAmsId,
			@Variable Integer debtorDisposalAccountAmsId,
			@Variable Integer debtorConversionAccountAmsId,
			@Variable BigDecimal transactionFeeAmount,
			@Variable String tenantIdentifier,
			@Variable String transactionGroupId,
			@Variable String transactionCategoryPurposeCode,
			@Variable String transactionFeeCategoryPurposeCode) {
		try {
			
			logger.debug("Incoming pain.001: {}", originalPain001);
			
			ObjectMapper om = new ObjectMapper();
			Pain00100110CustomerCreditTransferInitiationV10MessageSchema pain001 = om.readValue(originalPain001, Pain00100110CustomerCreditTransferInitiationV10MessageSchema.class);
			
			BankToCustomerStatementV08 convertedcamt053 = camt053Mapper.toCamt053(pain001.getDocument());
			String camt053 = om.writeValueAsString(convertedcamt053);
			
			String interbankSettlementDate = LocalDate.now().format(PATTERN);
			
            BatchItemBuilder biBuilder = new BatchItemBuilder(tenantIdentifier);
    		
    		String debtorDisposalWithdrawalRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), debtorDisposalAccountAmsId, "withdrawal");
    		
    		PaymentTypeConfig paymentTypeConfig = paymentTypeConfigFactory.getPaymentTypeConfig(tenantIdentifier);
    		Integer paymentTypeId = paymentTypeConfig.findPaymentTypeByOperation(String.format("%s.%s", paymentScheme, "transferTheAmountBetweenDisposalAccounts.Debtor.DisposalAccount.WithdrawTransactionAmount"));
    		
    		TransactionBody body = new TransactionBody(
    				interbankSettlementDate,
    				amount,
    				paymentTypeId,
    				"",
    				FORMAT,
    				locale);
    		
    		String bodyItem = om.writeValueAsString(body);
    		
    		List<TransactionItem> items = new ArrayList<>();
    		
    		biBuilder.add(items, debtorDisposalWithdrawalRelativeUrl, bodyItem, false);
    	
    		String camt053RelativeUrl = String.format("datatables/transaction_details/%d", debtorDisposalAccountAmsId);
    		
    		TransactionDetails td = new TransactionDetails(
    				"$.resourceId",
    				internalCorrelationId,
    				camt053,
    				transactionGroupId,
    				transactionCategoryPurposeCode);
    		
    		String camt053Body = om.writeValueAsString(td);

    		biBuilder.add(items, camt053RelativeUrl, camt053Body, true);
    		
			
			if (!BigDecimal.ZERO.equals(transactionFeeAmount)) {
				paymentTypeId = paymentTypeConfig.findPaymentTypeByOperation(String.format("%s.%s", paymentScheme, "transferTheAmountBetweenDisposalAccounts.Debtor.DisposalAccount.WithdrawTransactionFee"));
	    		
	    		body = new TransactionBody(
	    				interbankSettlementDate,
	    				transactionFeeAmount,
	    				paymentTypeId,
	    				"",
	    				FORMAT,
	    				locale);
	    		
	    		bodyItem = om.writeValueAsString(body);
	    		
	    		biBuilder.add(items, debtorDisposalWithdrawalRelativeUrl, bodyItem, false);
	    	
	    		camt053RelativeUrl = String.format("datatables/transaction_details/%d", debtorDisposalAccountAmsId);
	    		
	    		td = new TransactionDetails(
	    				"$.resourceId",
	    				internalCorrelationId,
	    				camt053,
	    				transactionGroupId,
	    				transactionFeeCategoryPurposeCode);
	    		
	    		camt053Body = om.writeValueAsString(td);
	    		biBuilder.add(items, camt053RelativeUrl, camt053Body, true);

	    		
	    		
				paymentTypeId = paymentTypeConfig.findPaymentTypeByOperation(String.format("%s.%s", paymentScheme, "transferTheAmountBetweenDisposalAccounts.Debtor.ConversionAccount.DepositTransactionFee"));
	    		
	    		body = new TransactionBody(
	    				interbankSettlementDate,
	    				transactionFeeAmount,
	    				paymentTypeId,
	    				"",
	    				FORMAT,
	    				locale);
			    		
	    		bodyItem = om.writeValueAsString(body);
	    		
	    		String debtorConversionDepositRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), debtorConversionAccountAmsId, "deposit");
		    		
	    		biBuilder.add(items, debtorConversionDepositRelativeUrl, bodyItem, false);
		    	
	    		camt053RelativeUrl = String.format("datatables/transaction_details/%d", debtorConversionAccountAmsId);
	    		
	    		td = new TransactionDetails(
	    				"$.resourceId",
	    				internalCorrelationId,
	    				camt053,
	    				transactionGroupId,
	    				transactionFeeCategoryPurposeCode);
	    		
	    		camt053Body = om.writeValueAsString(td);
	    		biBuilder.add(items, camt053RelativeUrl, camt053Body, true);
			}
			
			paymentTypeId = paymentTypeConfig.findPaymentTypeByOperation(String.format("%s.%s", paymentScheme, "transferTheAmountBetweenDisposalAccounts.Creditor.DisposalAccount.DepositTransactionAmount"));
    		
    		body = new TransactionBody(
    				interbankSettlementDate,
    				amount,
    				paymentTypeId,
    				"",
    				FORMAT,
    				locale);
		    		
    		bodyItem = om.writeValueAsString(body);
    		
    		String creditorDisposalDepositRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), creditorDisposalAccountAmsId, "deposit");
	    		
    		biBuilder.add(items, creditorDisposalDepositRelativeUrl, bodyItem, false);
	    	
    		camt053RelativeUrl = String.format("datatables/transaction_details/%d", creditorDisposalAccountAmsId);
    		td = new TransactionDetails(
    				"$.resourceId",
    				internalCorrelationId,
    				camt053,
    				transactionGroupId,
    				transactionCategoryPurposeCode);
    		
    		camt053Body = om.writeValueAsString(td);
    		biBuilder.add(items, camt053RelativeUrl, camt053Body, true);
			
	    		
			if (!BigDecimal.ZERO.equals(transactionFeeAmount)) {
	    		paymentTypeId = paymentTypeConfig.findPaymentTypeByOperation(String.format("%s.%s", paymentScheme, "transferTheAmountBetweenDisposalAccounts.Debtor.ConversionAccount.WithdrawTransactionFee"));
	    		
	    		body = new TransactionBody(
	    				interbankSettlementDate,
	    				transactionFeeAmount,
	    				paymentTypeId,
	    				"",
	    				FORMAT,
	    				locale);
			    		
	    		bodyItem = om.writeValueAsString(body);
	    		
	    		String debtorConversionWithdrawRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), debtorConversionAccountAmsId, "withdrawal");
		    		
	    		biBuilder.add(items, debtorConversionWithdrawRelativeUrl, bodyItem, false);
		    	
	    		camt053RelativeUrl = String.format("datatables/transaction_details/%d", debtorConversionAccountAmsId);
			    		
	    		biBuilder.add(items, camt053RelativeUrl, camt053Body, true);
			}
			
			doBatch(items, tenantIdentifier, internalCorrelationId);
			
			return Map.of("transactionDate", interbankSettlementDate);
		} catch (JsonProcessingException e) {
			logger.error(e.getMessage(), e);
			throw new ZeebeBpmnError(ERROR_FAILED_CREDIT_TRANSFER, e.getMessage());
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new ZeebeBpmnError(activatedJob.getBpmnProcessId(), e.getMessage());
		}
	}
}
