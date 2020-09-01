package com.drajer.eca.model;

import java.util.List;

import java.time.LocalDateTime;

import org.hl7.fhir.r4.model.PlanDefinition.ActionRelationshipType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.drajer.eca.model.EventTypes.EcrActionTypes;
import com.drajer.eca.model.EventTypes.JobStatus;
import com.drajer.eca.model.EventTypes.WorkflowEvent;
import com.drajer.ecrapp.model.Eicr;
import com.drajer.ecrapp.service.WorkflowService;
import com.drajer.ecrapp.util.ApplicationUtils;
import com.drajer.sof.model.LaunchDetails;


public class PeriodicUpdateEicrAction extends AbstractAction {

	private final Logger logger = LoggerFactory.getLogger(PeriodicUpdateEicrAction.class);

	@Override
	public void execute(Object obj, WorkflowEvent launchType) {
		
		logger.info(" **** START Executing Periodic Update Eicr Action **** ");

		if (obj instanceof LaunchDetails) {

			LaunchDetails details = (LaunchDetails) obj;
			PatientExecutionState state = null;
			PeriodicUpdateEicrStatus status = new PeriodicUpdateEicrStatus();

			state = EcaUtils.getDetailStatus(details);
			status.setActionId(getActionId());
			
			logger.info(" Executing Periodic Update Eicr Action , Prior Execution State : = {}" , details.getStatus());

			// Handle Conditions
			Boolean conditionsMet = true;
			conditionsMet = matchCondition(details);

			// PreConditions Met, then process related actions.
			Boolean relatedActsDone = true;
			if (conditionsMet) {
				
				logger.info(" PreConditions have been Met, evaluating Related Actions. ");

				if (getRelatedActions() != null && getRelatedActions().size() > 0) {

					List<RelatedAction> racts = getRelatedActions();

					for (RelatedAction ract : racts) {

						// Check for all actions AFTER which this action has to be executed for completion.
						if (ract.getRelationship() == ActionRelationshipType.AFTER) {

							// check if the action is completed.
							String actionId = ract.getRelatedAction().getActionId();

							if (!state.hasActionCompleted(actionId)) {

								logger.info(
										" Action {} is not completed , hence this action has to wait ",actionId);
								relatedActsDone = false;
							}
							else {
								
								logger.info(" Related Action has been completed : {}" , actionId);
								
								// Check if there is any timing constraint that needs to be handled.
								if(ract.getDuration() != null && 
									state.getPeriodicUpdateJobStatus() == JobStatus.NOT_STARTED) {
									
									// Duration is not null, meaning that the create action has to be delayed by the duration.
									logger.info(" Schedule the job for Priodic Update EICR based on the duration.");
								
									WorkflowService.scheduleJob(details.getId(), ract.getDuration(), EcrActionTypes.PERIODIC_UPDATE_EICR);
									
									state.setPeriodicUpdateJobStatus(JobStatus.SCHEDULED);	
									state.getPeriodicUpdateStatus().add(status);
									
									EcaUtils.updateDetailStatus(details, state);
									// No need to continue as the job will take over execution.
									logger.info(" **** End Executing Periodic Update Eicr Action **** ");
									return;
									
								}
								else {
									
									logger.info( " No need to scheuled job as it has already been scheduled or completed. ");
								}
							}
						}
						else {
							logger.info(" Action {} is related via {}",ract.getRelatedAction().getActionId(),ract.getRelationship());
							
						}
					}
				}
				
				// Check Timing Data , No need to check if the state is already scheduled meaning the
				// job was scheduled already.
				if (relatedActsDone) {
					
					logger.info(" All Related Actions are completed ");

					// Timing constraints are applicable if this job has not started, once it is started
					// the State Machine has to manage the execution.
					if (state.getPeriodicUpdateJobStatus() == JobStatus.NOT_STARTED) {
						
						logger.info(" Related Actions Done and this action has not started ");
						
						if (getTimingData() != null && getTimingData().size() > 0) {
							
							logger.info(" Timing Data is present , so create a job based on timing data.");
							
							scheduleJob(details, state, status);
							return;
						}
						
						logger.info(" No job to schedule since there is no timing data ");
					}
					else if (state.getPeriodicUpdateJobStatus() == JobStatus.SCHEDULED &&
							 !state.getCreateEicrStatus().getEicrCreated() && 
							 state.getCloseOutEicrStatus().getJobStatus() != JobStatus.COMPLETED) {
					
						// Do this only if the job is scheduled.
						logger.info(" Creating the Periodic Update EICR since the job has been scheduled ");
						
						// Check Trigger Codes again in case the data has changed.
						PatientExecutionState newState = recheckTriggerCodes(details, launchType);
						
						if(newState.getMatchTriggerStatus().getTriggerMatchStatus() && 
						   newState.getMatchTriggerStatus().getMatchedCodes() != null && 
						   newState.getMatchTriggerStatus().getMatchedCodes().size() > 0) {

							// Since the job has started, Execute the job.
							// Call the Loading Queries and create eICR.
							Eicr ecr = EcaUtils.createEicr(details);

							if(ecr != null) {

								newState.getCreateEicrStatus().setEicrCreated(true);
								newState.getCreateEicrStatus().seteICRId(ecr.getId().toString());
								newState.getCreateEicrStatus().setJobStatus(JobStatus.COMPLETED);

								newState.setPeriodicUpdateJobStatus(JobStatus.COMPLETED);
								newState.getPeriodicUpdateStatus().add(status);

								EcaUtils.updateDetailStatus(details, newState);

								logger.info(" **** Printing Eicr from Periodic Update EICR ACTION **** ");

								logger.info(ecr.getData());

								String fileName = ActionRepo.getInstance().getLogFileDirectory() + "/" + details.getLaunchPatientId() + "_PeriodicUpdateEicrAction" 
										+ LocalDateTime.now().getHour()+LocalDateTime.now().getMinute()+LocalDateTime.now().getSecond()+ ".xml";
								ApplicationUtils.saveDataToFile(ecr.getData(), fileName);

								logger.info(" **** End Printing Eicr from Periodic Update EICR ACTION **** ");
							}

						}// Check if Trigger Code Match found 
						else {
							
							logger.info(" **** Trigger Code did not match, hence not creating EICR **** ");
							
							// Schedule job again.
							if (getTimingData() != null && getTimingData().size() > 0) {
								
								logger.info(" Timing Data is present , so create a job based on timing data.");
								
								scheduleJob(details, state, status);
								return;
							}
						}
					}
					else {
						logger.info(" Periodic Update not needed , due to which EICR will not be created. ");
					}

				}
				else {
					logger.info(" Related Actions are not completed, hence EICR will not be created. ");
				}
				
			}
			else {
				
				logger.info(" Conditions not met, hence EICR will not be created. ");
			}
		}
		else {
			
			String msg = "Invalid Object passed to Execute method, Launch Details expected, found : " + obj.getClass().getName();
			logger.error(msg);
			
			throw new RuntimeException(msg);
			
		}
		
		logger.info(" **** END Executing Create Eicr Action after completing normal execution. **** ");
	}
	
	private void scheduleJob(LaunchDetails details, PatientExecutionState state, PeriodicUpdateEicrStatus status){
			
		List<TimingSchedule> tsjobs = getTimingData();

		for (TimingSchedule ts : tsjobs) {

			// TBD : Setup job using TS Timing after testing so that we can test faster.
			// For now setup a default job with 10 seconds.
			WorkflowService.scheduleJob(details.getId(), ts, EcrActionTypes.PERIODIC_UPDATE_EICR);

			state.setPeriodicUpdateJobStatus(JobStatus.SCHEDULED);	
			state.getPeriodicUpdateStatus().add(status);

			EcaUtils.updateDetailStatus(details, state);
			// No need to continue as the job will take over execution.
			logger.info(" **** End Executing Periodic Update Eicr Action **** ");
			return;
		}
	}
	
	@Override
	public void print() {
		
		logger.info(" **** Printing PeriodicUpdateEicrAction **** ");
		printBase();
		logger.info(" **** End Printing PeriodicUpdateEicrAction **** ");
	}

}
