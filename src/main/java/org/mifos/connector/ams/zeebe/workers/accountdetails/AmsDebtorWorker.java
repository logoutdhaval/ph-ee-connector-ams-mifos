package org.mifos.connector.ams.zeebe.workers.accountdetails;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import io.camunda.zeebe.spring.client.exception.ZeebeBpmnError;

@Component
public class AmsDebtorWorker extends AbstractAmsWorker {

	Logger logger = LoggerFactory.getLogger(AmsDebtorWorker.class);
	
	public AmsDebtorWorker() {
	}
	
	public AmsDebtorWorker(RestTemplate restTemplate) {
		super(restTemplate);
	}

	@JobWorker
	public Map<String, Object> getAccountIdsFromAms(JobClient jobClient, 
			ActivatedJob activatedJob,
			@Variable String debtorIban,
			@Variable String tenantIdentifier) throws Exception {
		logger.info(">>>>>>>>>>>>>>>>>>> AMS debtor worker started <<<<<<<<<<<<<<<<<");

		logger.debug(">>>>>>>>>>>>>>>>>>> looking up debtor iban {} for tenant {} <<<<<<<<<<<<<<<<<<", debtorIban, tenantIdentifier);
		
		AmsDataTableQueryResponse[] lookupAccount = lookupAccount(debtorIban, tenantIdentifier);
		
		if (lookupAccount.length == 0) {
			logger.error("####################  No entry found for iban {} !!!  #########################", debtorIban);
			throw new ZeebeBpmnError(debtorIban, String.format("No entry found for iban %s", debtorIban));
		} else {
			AmsDataTableQueryResponse responseItem = lookupAccount[0];
		
			return Map.of("disposalAccountAmsId", responseItem.disposal_account_id(),
					"conversionAccountAmsId", responseItem.conversion_account_id());
		}
	}
}
