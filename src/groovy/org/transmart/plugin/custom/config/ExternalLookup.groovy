package org.transmart.plugin.custom.config

import com.bettercloud.vault.SslConfig
import com.bettercloud.vault.Vault
import com.bettercloud.vault.VaultConfig
import com.bettercloud.vault.VaultException
import com.bettercloud.vault.api.Logical
import com.bettercloud.vault.response.HealthResponse
import com.bettercloud.vault.response.LogicalResponse
import com.bettercloud.vault.rest.RestResponse
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * @author <a href='mailto:burt_beckwith@hms.harvard.edu'>Burt Beckwith</a>
 */
@CompileStatic
@Slf4j('logger')
class ExternalLookup {

	private static final MISSING = new Object() {
		String toString() { 'MISSING' }
	}
	private static final String ROOT = 'secret/TRANSMART/'

	private final Comparator<String> comparator = new Comparator<String>() {
		int compare(String s1, String s2) {
			int dc1 = s1.split('\\.').length
			int dc2 = s2.split('\\.').length
			dc1 == dc2 ? s1 <=> s2 : dc1 <=> dc2
		}
	}
	private final SortedSet<String> allVaultPropertyNames = new TreeSet<String>(comparator)
	private final GroovyObject config
	private final Map<String, String> propertyNamesToEnvVarNames
	private final Logical logical
	private final Vault vault

	ExternalLookup(GroovyObject config, Map<String, String> propertyNamesToEnvVarNames = null) throws VaultException {
		this.config = config
		this.propertyNamesToEnvVarNames = propertyNamesToEnvVarNames ?: [:] as Map
		vault = new Vault(new VaultConfig()
				.address('http://127.0.0.1:8200') // VAULT_ADDR
				.token('00000000-0000-0000-0000-000000000000') // VAULT_TOKEN
				.openTimeout(5) // VAULT_OPEN_TIMEOUT
				.readTimeout(30) // VAULT_READ_TIMEOUT
				.sslConfig(new SslConfig().verify(false).build()) // VAULT_SSL_VERIFY
				.build())
		logical = vault.logical()
		allVaultPropertyNames.addAll vaultPropertyNames()

		HealthResponse healthResponse = vault.debug().health()
		logger.debug 'HealthResponse: initialized = {}, retries = {}, sealed = {}, standby = {}, server date = {}, response JSON = {}',
				healthResponse.initialized, healthResponse.retries, healthResponse.sealed, healthResponse.standby,
				new Date(healthResponse.serverTimeUTC * 1000), jsonFromResponse(healthResponse.restResponse)

		logger.debug 'all Vault property names: {}', allVaultPropertyNames
	}

	void setFromExternalValue(String propertyName, Class type = String) throws VaultException {
		setConfigValue propertyName, convertValue(getExternalValue(propertyName), type)
	}

	void setBooleanFromExternalValue(String propertyName, boolean invert = false) throws VaultException {
		boolean value = convertValue(getExternalValue(propertyName), Boolean)
		setConfigValue propertyName, invert ? !value : value
	}

	def getExternalValue(String propertyName) throws VaultException {
		def value = vaultValue(propertyName)
		if (MISSING.is(value)) {
			value = envValue(propertyName)
		}
		if (MISSING.is(value)) {
			value = null
		}
		value
	}

	private vaultValue(String propertyName) throws VaultException {
		if (allVaultPropertyNames.contains(propertyName)) {
			String path = propertyNameToPath(propertyName)
			try {
				LogicalResponse response = logical.read(path)
				traceResponse response, 'read', propertyName, path
				response.data.value
			}
			catch (VaultException e) {
				if (e.httpStatusCode == 404) {
					logger.warn 'unexpected 404 from Vault for property "{}" ("{}")', propertyName, path
					MISSING
				}
				else {
					throw e
				}
			}
		}
		else {
			MISSING
		}
	}

	private envValue(String propertyName) {
		String envVarName = propertyNamesToEnvVarNames[propertyName]
		assert envVarName
		if (System.getenv().containsKey(envVarName)) {
			System.getenv envVarName
		}
		else {
			MISSING
		}
	}

	@CompileDynamic
	private <T> T convertValue(value, Class<T> type) {
		if (value == null) {
			null
		}
		else if (type.isAssignableFrom(value.getClass())) {
			value
		}
		else if (type == Boolean) {
			Boolean.valueOf value
		}
		else if (type == List) {
			(!value || value == 'null') ? [] : Eval.me(value)
		}
		else {
			value.asType type
		}
	}

	private void setConfigValue(String propertyName, value) {
		List<String> parts = propertyName.split('\\.') as List
		String last = parts.remove(parts.size() - 1)
		GroovyObject current = config
		for (String part in parts) {
			current = (GroovyObject) current.getProperty(part)
		}
		current.setProperty last, value

		if (value instanceof CharSequence) {
			logger.trace 'setProperty "{}" -> "{}"', propertyName, value
		}
		else {
			logger.trace 'setProperty "{}" -> {}', propertyName, value
		}
	}

	private List<String> vaultPropertyNames() throws VaultException {
		List<String> paths = []
		getVaultPropertyNames ROOT, paths
		paths
	}

	private void getVaultPropertyNames(String path, List<String> paths) throws VaultException {
		for (String subPath in logical.list(path)) {
			if (subPath.endsWith('/')) {
				getVaultPropertyNames path + subPath, paths
			}
			else {
				paths << pathNameToProperty(path + subPath)
			}
		}

		paths
	}

	private String propertyNameToPath(String propertyName) {
		ROOT + propertyName.replaceAll('\\.', '/')
	}

	private String pathNameToProperty(String path) {
		(path - ROOT).replaceAll('/', '.')
	}

	private void traceResponse(LogicalResponse response, prefix1, prefix2, prefix3) {
		logger.trace '{} {} {} LogicalResponse; leaseDuration: {}, leaseId: {}, renewable: {}, retries: {}, data: {}, JSON: {}',
				prefix1, prefix2, prefix3, response.leaseDuration, response.leaseId,
				response.renewable, response.retries, response.data, jsonFromResponse(response.restResponse)
	}

	private String jsonFromResponse(RestResponse restResponse) {
		restResponse.body ? new String(restResponse.body, 'UTF-8') : ''
	}
}
