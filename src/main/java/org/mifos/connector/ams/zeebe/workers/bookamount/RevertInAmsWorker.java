package org.mifos.connector.ams.zeebe.workers.bookamount;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.mifos.connector.ams.fineract.PaymentTypeConfig;
import org.mifos.connector.ams.fineract.PaymentTypeConfigFactory;
import org.mifos.connector.ams.mapstruct.Pain001Camt052Mapper;
import org.mifos.connector.ams.zeebe.workers.utils.BatchItemBuilder;
import org.mifos.connector.ams.zeebe.workers.utils.TransactionBody;
import org.mifos.connector.ams.zeebe.workers.utils.TransactionDetails;
import org.mifos.connector.ams.zeebe.workers.utils.TransactionItem;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import iso.std.iso._20022.tech.json.camt_052_001.BankToCustomerAccountReportV08;
import iso.std.iso._20022.tech.json.pain_001_001.CreditTransferTransaction40;
import iso.std.iso._20022.tech.json.pain_001_001.Pain00100110CustomerCreditTransferInitiationV10MessageSchema;

@Component
public class RevertInAmsWorker extends AbstractMoneyInOutWorker {
	
	@Autowired
	private Pain001Camt052Mapper camt052Mapper;
	
	@Value("${fineract.incoming-money-api}")
	protected String incomingMoneyApi;
	
	@Value("${fineract.auth-token}")
	private String authToken;
	
	@Autowired
    private PaymentTypeConfigFactory paymentTypeConfigFactory;
	
	@Override
	public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {
		Map<String, Object> variables = activatedJob.getVariablesAsMap();
		
		String internalCorrelationId = (String) variables.get("internalCorrelationId");
		MDC.put("internalCorrelationId", internalCorrelationId);
		
		String originalPain001 = (String) variables.get("originalPain001");
		
		Integer conversionAccountAmsId = (Integer) variables.get("conversionAccountAmsId");
		
		String transactionDate = (String) variables.get("transactionDate");

		Integer disposalAccountAmsId = (Integer) variables.get("disposalAccountAmsId");
		
		String paymentScheme = (String) variables.get("paymentScheme");
		
		ObjectMapper om = new ObjectMapper();
		Pain00100110CustomerCreditTransferInitiationV10MessageSchema pain001 = om.readValue(originalPain001, Pain00100110CustomerCreditTransferInitiationV10MessageSchema.class);
		
		BigDecimal amount = BigDecimal.ZERO;
		BigDecimal fee = variables.get("transactionFeeAmount") == null
						? null
						: new BigDecimal(variables.get("transactionFeeAmount").toString());
		
		List<CreditTransferTransaction40> creditTransferTransactionInformation = pain001.getDocument().getPaymentInformation().get(0).getCreditTransferTransactionInformation();
		
		for (CreditTransferTransaction40 ctti : creditTransferTransactionInformation) {
			amount = new BigDecimal(ctti.getAmount().getInstructedAmount().getAmount().toString());
		}
		
		logger.error("Withdrawing amount {} from conversion account {}", amount, conversionAccountAmsId);
		
		String tenantId = (String) variables.get("tenantIdentifier");
		
		
		
		
		
		
		BatchItemBuilder biBuilder = new BatchItemBuilder(tenantId);
		
		String conversionAccountWithdrawRelativeUrl = String.format("%s/%d/transactions?command=%s", incomingMoneyApi, conversionAccountAmsId, "withdrawal");
		
		PaymentTypeConfig paymentTypeConfig = paymentTypeConfigFactory.getPaymentTypeConfig(tenantId);
		Integer paymentTypeId = paymentTypeConfig.findPaymentTypeByOperation(String.format("%s.%s", paymentScheme, "revertInAms.ConversionAccount.WithdrawTransactionAmount"));
		
		TransactionBody body = new TransactionBody(
				transactionDate,
				amount,
				paymentTypeId,
				"",
				FORMAT,
				locale);
		
		String bodyItem = om.writeValueAsString(body);
		
		List<TransactionItem> items = new ArrayList<>();
		
		biBuilder.add(items, conversionAccountWithdrawRelativeUrl, bodyItem, false);
		
		BankToCustomerAccountReportV08 convertedCamt052 = camt052Mapper.toCamt052(pain001.getDocument());
		String camt052 = om.writeValueAsString(convertedCamt052);
		
		String camt052RelativeUrl = String.format("datatables/transaction_details/%d", disposalAccountAmsId);
		
		TransactionDetails td = new TransactionDetails(
				"$.resourceId",
				internalCorrelationId,
				camt052);
		
		String camt052Body = om.writeValueAsString(td);

		biBuilder.add(items, camt052RelativeUrl, camt052Body, true);
		
		
		
		
		
		
	
		ResponseEntity<Object> responseObject = withdraw(
				transactionDate, 
				amount, 
				conversionAccountAmsId, 
				paymentScheme,
				"revertInAms.ConversionAccount.WithdrawTransactionAmount",
				tenantId, 
				internalCorrelationId);
			
		if (!HttpStatus.OK.equals(responseObject.getStatusCode())) {
			jobClient.newFailCommand(activatedJob.getKey()).retries(0).send();
			return;
		}
		
		postCamt052(tenantId, camt052, internalCorrelationId, responseObject);
		
		if (!fee.equals(BigDecimal.ZERO)) {
			logger.error("Withdrawing fee {} from conversion account {}", fee, conversionAccountAmsId);
			
			paymentTypeId = paymentTypeConfig.findPaymentTypeByOperation(String.format("%s.%s", paymentScheme, "revertInAms.ConversionAccount.WithdrawTransactionFee"));
			
			body = new TransactionBody(
					transactionDate,
					fee,
					paymentTypeId,
					"",
					FORMAT,
					locale);
			
			bodyItem = om.writeValueAsString(body);
			
			biBuilder.add(items, conversionAccountWithdrawRelativeUrl, bodyItem, false);
			
			biBuilder.add(items, camt052RelativeUrl, camt052Body, true);
		}

		logger.error("Re-depositing amount {} in disposal account {}", amount, disposalAccountAmsId);
		
		String disposalAccountDepositRelativeUrl = String.format("%s/%d/transactions?command=%s", incomingMoneyApi, disposalAccountAmsId, "deposit");
		
		paymentTypeId = paymentTypeConfig.findPaymentTypeByOperation(String.format("%s.%s", paymentScheme, "revertInAms.DisposalAccount.DepositTransactionAmount"));
		
		body = new TransactionBody(
				transactionDate,
				amount,
				paymentTypeId,
				"",
				FORMAT,
				locale);
		
		bodyItem = om.writeValueAsString(body);
		
		biBuilder.add(items, disposalAccountDepositRelativeUrl, bodyItem, false);
		
		biBuilder.add(items, camt052RelativeUrl, camt052Body, true);
		
		if (!fee.equals(BigDecimal.ZERO)) {
			logger.error("Re-depositing fee {} in disposal account {}", fee, disposalAccountAmsId);
			
			paymentTypeId = paymentTypeConfig.findPaymentTypeByOperation(String.format("%s.%s", paymentScheme, "revertInAms.DisposalAccount.DepositTransactionFee"));
			
			body = new TransactionBody(
					transactionDate,
					fee,
					paymentTypeId,
					"",
					FORMAT,
					locale);
			
			bodyItem = om.writeValueAsString(body);
			
			biBuilder.add(items, disposalAccountDepositRelativeUrl, bodyItem, false);
			
			biBuilder.add(items, camt052RelativeUrl, camt052Body, true);
		}
		
		doBatch(items, tenantId, internalCorrelationId);
		
		jobClient.newCompleteCommand(activatedJob.getKey()).variables(variables).send();
		MDC.remove("internalCorrelationId");
	}

}
