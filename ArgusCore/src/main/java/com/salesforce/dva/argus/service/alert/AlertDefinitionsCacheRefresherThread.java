/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of Salesforce.com nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.dva.argus.service.alert;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.salesforce.dva.argus.entity.Alert;
import com.salesforce.dva.argus.service.AlertService;
import com.salesforce.dva.argus.service.MonitorService.Counter;

public class AlertDefinitionsCacheRefresherThread extends Thread {

    private final Logger _logger = LoggerFactory.getLogger(AlertDefinitionsCacheRefresherThread.class);

    // keeping the refresh interval at 1 minute, as this corresponds to the minimum alert execution interval based on cron expression
    private static final Long REFRESH_INTERVAL_MILLIS = 60 * 1000L;

    private static final Long LOOKBACK_PERIOD_FOR_REFRESH_MILLIS = 5 * REFRESH_INTERVAL_MILLIS;

    private AlertDefinitionsCache alertDefinitionsCache = null;

    private AlertService alertService;

    public AlertDefinitionsCacheRefresherThread(AlertDefinitionsCache cache, AlertService alertService) {
        this.alertDefinitionsCache = cache;
        this.alertService = alertService;
    }

    public void run() {
        long lastExecutionTime = 0L;
        while (!isInterrupted()) {
            long executionTime = 0L, currentExecutionTime = 0L;
            try {
                long startTime = System.currentTimeMillis();
                if (!alertDefinitionsCache.isAlertsCacheInitialized()) {
                    _logger.info("Starting alert definitions cache initialization");
                    lastExecutionTime = System.currentTimeMillis();
                    initializeAlertDefinitionsCache();
                } else {
                    _logger.info("Starting alert definitions cache refresh");
                    currentExecutionTime = System.currentTimeMillis();
                    refreshAlertDefinitionsCache(startTime, executionTime, lastExecutionTime, currentExecutionTime);
                }

                if (lastExecutionTime > 0) {
                    _logger.info("AlertCache was refreshed after {} millisec", currentExecutionTime - lastExecutionTime);
                }

                lastExecutionTime = currentExecutionTime;
                executionTime = System.currentTimeMillis() - startTime;
                _logger.info("Alerts cache refresh was executed successfully in {} millis. Number of alerts in cache - {}", executionTime, alertDefinitionsCache.getAlertsMapById().keySet().size());
                if (executionTime < REFRESH_INTERVAL_MILLIS) {
                    sleep(REFRESH_INTERVAL_MILLIS - executionTime);
                }
            } catch (Exception e) {
                _logger.error("Exception occurred when trying to refresh alert definition cache - " + ExceptionUtils.getFullStackTrace(e));
            }
        }
    }

    void refreshAlertDefinitionsCache(long startTime, long executionTime, long lastExecutionTime, long currentExecutionTime) {
        List<Alert> modifiedAlerts = alertService.findAlertsModifiedAfterDate(new Date(startTime - Math.max(executionTime + REFRESH_INTERVAL_MILLIS, LOOKBACK_PERIOD_FOR_REFRESH_MILLIS)));

        // updating only the modified/deleted alerts in the cache
        long sumTimeToDiscover = 0L;
        long sumTimeToDiscoverNew = 0L;
        int newAlertsCount = 0;
        int updatedAlertsCount = 0;
        if (modifiedAlerts != null && modifiedAlerts.size() > 0) {
            for (Alert a : modifiedAlerts) {
                long timeToDiscover = 0;
                _logger.debug("Processing modified alert - {},{},{},{} after {} milliseconds ", a.getId(),
                        a.getName(), a.getCronEntry(), a.getExpression(), timeToDiscover);

                boolean isValid = checkIsValidAlert(a);

                if (alertDefinitionsCache.getAlertsMapById().containsKey(a.getId())) {
                    timeToDiscover = currentExecutionTime - a.getModifiedDate().getTime();
                    if (a.isDeleted() || !a.isEnabled() || !isValid) {
                        alertDefinitionsCache.getAlertsMapById().remove(a.getId());
                        removeEntryFromCronMap(a.getId());
                        sumTimeToDiscover += timeToDiscover;
                        updatedAlertsCount++;
                        _logger.debug("Found updated alert {} to be removed from cache which was updated at {}, created at {} lastExecutionTime {}, currentExecutionTime {}, timeToDiscover {}",
                                a.getId().toString(), a.getModifiedDate().getTime(), a.getCreatedDate().getTime(), lastExecutionTime, currentExecutionTime, timeToDiscover);
                    } else {
                        Alert alertFromCache = alertDefinitionsCache.getAlertsMapById().get(a.getId());
                        boolean isAlertModified = !a.equals(alertFromCache);
                        _logger.debug("Reading alert from cache to check if it needs to be updated: Alert {} which was updated at {}, created at {} lastExecutionTime {}, currentExecutionTime {}, timeToDiscover {}",
                                alertFromCache.getId().toString(), alertFromCache.getModifiedDate().getTime(), alertFromCache.getCreatedDate().getTime(), lastExecutionTime, currentExecutionTime, timeToDiscover);

                        // removing the previous cron mapping and adding fresh only in case the mapping changed
                        if (isAlertModified) {
                            removeEntryFromCronMap(a.getId());
                            alertDefinitionsCache.getAlertsMapById().put(a.getId(), a);
                            addEntrytoCronMap(a);
                            sumTimeToDiscover += timeToDiscover;
                            updatedAlertsCount++;
                        }
                    }
                } else if (a.isEnabled() && !a.isDeleted() && isValid) {
                    timeToDiscover = currentExecutionTime - a.getCreatedDate().getTime();
                    sumTimeToDiscoverNew += timeToDiscover;
                    newAlertsCount++;
                    alertDefinitionsCache.getAlertsMapById().put(a.getId(), a);
                    addEntrytoCronMap(a);
                    _logger.debug("Found a new alert {} which was created at {}, lastExecutionTime {}, currentExecutionTime {}, timeToDiscover {}",
                            a.getId().toString(), a.getCreatedDate().getTime(), lastExecutionTime, currentExecutionTime, timeToDiscover);
                }
            }
        }

        alertService.updateCounter(Counter.ALERTS_UPDATED_COUNT, (double) updatedAlertsCount);
        _logger.info("Number of modified alerts since last refresh - " + updatedAlertsCount);

        alertService.updateCounter(Counter.ALERTS_CREATED_COUNT, (double) newAlertsCount);
        _logger.info("Number of created alerts since last refresh - " + newAlertsCount);

        if (updatedAlertsCount > 0) {
            long avgTimeToDiscover = sumTimeToDiscover / updatedAlertsCount;
            alertService.updateCounter(Counter.ALERTS_UPDATE_LATENCY, (double) avgTimeToDiscover);
            _logger.info("Average time to discovery of change - " + avgTimeToDiscover + " milliseconds");
        }

        if (newAlertsCount > 0) {
            _logger.info("Number of created alerts since last refresh - " + newAlertsCount);
            long avgTimeToDiscoverNewAlert = sumTimeToDiscoverNew / newAlertsCount;
            alertService.updateCounter(Counter.ALERTS_NEW_LATENCY, (double) avgTimeToDiscoverNewAlert);
            _logger.info("Average time to discovery of new alert - " + avgTimeToDiscoverNewAlert + " milliseconds");
        }
    }

    void initializeAlertDefinitionsCache() {
        List<Alert> enabledAlerts = alertService.findAlertsByStatus(true);
        Map<BigInteger, Alert> enabledValidAlertsMap = enabledAlerts.stream().
                filter(this::checkIsValidAlert).
                collect(Collectors.toMap(alert -> alert.getId(), alert -> alert));
        for (Alert a : enabledAlerts) {
            if (enabledValidAlertsMap.containsKey(a.getId())) {
                addEntrytoCronMap(a);
            }
        }
        alertDefinitionsCache.setAlertsMapById(enabledValidAlertsMap);
        alertDefinitionsCache.setAlertsCacheInitialized(true);
    }

    private void addEntrytoCronMap(Alert a) {
        if (alertDefinitionsCache.getAlertsMapByCronEntry().get(a.getCronEntry()) == null) {
            alertDefinitionsCache.getAlertsMapByCronEntry().put(a.getCronEntry(), new ArrayList<>());
        }
        alertDefinitionsCache.getAlertsMapByCronEntry().get(a.getCronEntry()).add(a.getId());
    }

    private void removeEntryFromCronMap(BigInteger alertId) {
        for (String cronEntry : alertDefinitionsCache.getAlertsMapByCronEntry().keySet()) {
            if (alertDefinitionsCache.getAlertsMapByCronEntry().get(cronEntry).contains(alertId)) {
                alertDefinitionsCache.getAlertsMapByCronEntry().get(cronEntry).remove(alertId);
            }
        }
    }

    private boolean checkIsValidAlert(Alert a) {
        if (!a.isValid()) {
            String msg = a.validationMessage();
            _logger.info("AlertDefinitionsCache: Excluding INVALID ALERT {},{},{},{} : {}",
                    a.getId(), a.getName(), a.getCronEntry(), a.getExpression(), msg);
            return false;
        }
        return true;
    }
}

