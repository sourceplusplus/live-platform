package spp.platform.core

import spp.protocol.developer.Developer
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.redis.client.RedisAPI
import org.apache.commons.lang3.RandomStringUtils
import org.slf4j.LoggerFactory
import spp.platform.core.auth.*
import java.nio.charset.StandardCharsets

object SourceStorage {

    private val log = LoggerFactory.getLogger(SourceStorage::class.java)

    lateinit var redis: RedisAPI

    suspend fun setup(redis: RedisAPI) {
        this.redis = redis
        installDefaults()
    }

    private suspend fun installDefaults() {
        //set default roles and permissions
        addRole(DeveloperRole.ROLE_MANAGER.roleName)
        addRole(DeveloperRole.ROLE_USER.roleName)
        addRoleToDeveloper("system", DeveloperRole.ROLE_MANAGER)
        RolePermission.values().forEach {
            addPermissionToRole(DeveloperRole.ROLE_MANAGER, it)
        }
        RolePermission.values().forEach {
            if (!it.manager) {
                addPermissionToRole(DeveloperRole.ROLE_USER, it)
            }
        }
    }

    suspend fun reset(): Boolean {
        getDataRedactions().forEach { removeDataRedaction(it.id) }
        getRoles().forEach { removeRole(it) }
        getDevelopers().forEach { removeDeveloper(it.id) }
        installDefaults()
        return true
    }

    suspend fun getDevelopers(): List<Developer> {
        val devIds = redis.smembers("developers:ids").await()
        return devIds.map { Developer(it.toString(StandardCharsets.UTF_8)) }
    }

    suspend fun getDeveloperByAccessToken(token: String): Developer? {
        val devId = redis.get("developers:access_tokens:$token").await()
            ?.toString(StandardCharsets.UTF_8) ?: return null
        return Developer(devId)
    }

    suspend fun hasAccessToken(token: String): Boolean {
        return redis.sismember("developers:access_tokens", token).await().toBoolean()
    }

    suspend fun hasRole(roleName: String): Boolean {
        val role = DeveloperRole.fromString(roleName)
        return redis.sismember("roles", role.roleName).await().toBoolean()
    }

    suspend fun removeRole(role: DeveloperRole): Boolean {
        getRolePermissions(role).forEach {
            removePermissionFromRole(role, it)
        }
        return redis.srem(listOf("roles", role.roleName)).await().toBoolean()
    }

    suspend fun addRole(roleName: String): Boolean {
        val role = DeveloperRole.fromString(roleName)
        return redis.sadd(listOf("roles", role.roleName)).await().toBoolean()
    }

    suspend fun hasDeveloper(id: String): Boolean {
        return redis.sismember("developers:ids", id).await().toBoolean()
    }

    suspend fun addDeveloper(id: String): Developer {
        return addDeveloper(id, RandomStringUtils.randomAlphanumeric(50))
    }

    suspend fun addDeveloper(id: String, token: String): Developer {
        redis.sadd(listOf("developers:ids", id)).await()
        redis.set(listOf("developers:access_tokens:$token", id)).await()
        redis.sadd(listOf("developers:access_tokens", token)).await()
        redis.set(listOf("developers:ids:$id:access_token", token)).await()
        addRoleToDeveloper(id, DeveloperRole.ROLE_USER)
        return Developer(id, token)
    }

    suspend fun removeDeveloper(id: String) {
        val accessToken = getAccessToken(id)
        redis.srem(listOf("developers:ids", id)).await()
        redis.del(listOf("developers:access_tokens:$accessToken")).await()
        redis.srem(listOf("developers:access_tokens", accessToken)).await()
        redis.del(listOf("developers:ids:$id:access_token")).await()
        redis.del(listOf("developers:$id:roles")).await()
    }

    suspend fun getAccessToken(id: String): String {
        return redis.get("developers:ids:$id:access_token").await().toString(StandardCharsets.UTF_8)
    }

    suspend fun setAccessToken(id: String, accessToken: String) {
        //remove existing token
        val existingToken = redis.get("developers:ids:$id:access_token").await()
        if (existingToken != null) {
            val existingTokenStr = existingToken.toString(StandardCharsets.UTF_8)
            if (existingTokenStr.equals(accessToken)) {
                return //no change in access token; ignore
            } else {
                redis.srem(listOf("developers:access_tokens", existingTokenStr)).await()
                redis.del(listOf("developers:access_tokens:$existingToken")).await()
            }
        } else {
            //add developer first
            redis.sadd(listOf("developers:ids", id)).await()
        }

        //set new token
        redis.set(listOf("developers:access_tokens:$accessToken", id)).await()
        redis.sadd(listOf("developers:access_tokens", accessToken)).await()
        redis.set(listOf("developers:ids:$id:access_token", accessToken)).await()
    }

    suspend fun getDeveloperRoles(developerId: String): List<DeveloperRole> {
        return redis.smembers("developers:$developerId:roles").await()
            .map { DeveloperRole.fromString(it.toString(StandardCharsets.UTF_8)) }
    }

    suspend fun getDeveloperAccessPermissions(developerId: String): List<AccessPermission> {
        return getDeveloperRoles(developerId).flatMap { getRoleAccessPermissions(it) }
    }

    suspend fun hasInstrumentAccess(developerId: String, locationPattern: String): Boolean {
        val permissions = getDeveloperAccessPermissions(developerId)
        if (permissions.isEmpty()) {
            if (log.isTraceEnabled) log.trace("Developer {} has full access", developerId)
            return true
        } else {
            if (log.isTraceEnabled) log.trace("Developer {} has permissions {}", developerId, permissions)
        }

        val devBlackLists = permissions.filter { it.type == AccessType.BLACK_LIST }
        val inBlackList = devBlackLists.any { it ->
            val patterns = it.locationPatterns.map {
                it.replace(".", "\\.").replace("*", ".+")
            }
            patterns.any { locationPattern.matches(Regex(it)) }
        }

        val devWhiteLists = permissions.filter { it.type == AccessType.WHITE_LIST }
        val inWhiteList = devWhiteLists.any { it ->
            val patterns = it.locationPatterns.map {
                it.replace(".", "\\.").replace("*", ".+")
            }
            patterns.any { locationPattern.matches(Regex(it)) }
        }

        return if (devWhiteLists.isEmpty()) {
            !inBlackList
        } else if (devBlackLists.isEmpty()) {
            inWhiteList
        } else {
            !inBlackList || inWhiteList
        }
    }

    suspend fun getRoleAccessPermissions(role: DeveloperRole): Set<AccessPermission> {
        val accessPermissions = redis.smembers("roles:${role.roleName}:access_permissions").await()
        return accessPermissions.map { getAccessPermission(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    suspend fun getAccessPermissions(): Set<AccessPermission> {
        val accessPermissions = redis.smembers("access_permissions").await()
        return accessPermissions.map { getAccessPermission(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    suspend fun hasAccessPermission(id: String): Boolean {
        return redis.sismember("access_permissions", id).await().toBoolean()
    }

    suspend fun getAccessPermission(id: String): AccessPermission {
        val accessPermissions = redis.get("access_permissions:$id").await()
        val dataObject = JsonObject(accessPermissions.toString(StandardCharsets.UTF_8))
        return AccessPermission(
            id,
            dataObject.getJsonArray("locationPatterns").map { it.toString() },
            AccessType.valueOf(dataObject.getString("type"))
        )
    }

    suspend fun addAccessPermission(id: String, locationPatterns: List<String>, type: AccessType) {
        redis.sadd(listOf("access_permissions", id)).await()
        redis.set(
            listOf(
                "access_permissions:$id",
                JsonObject()
                    .put("locationPatterns", locationPatterns)
                    .put("type", type.name)
                    .toString()
            )
        ).await()
    }

    suspend fun removeAccessPermission(id: String) {
        getRoles().forEach {
            removeAccessPermissionFromRole(id, it)
        }
        redis.srem(listOf("access_permissions", id)).await()
        redis.del(listOf("access_permissions:$id")).await()
    }

    suspend fun addAccessPermissionToRole(id: String, role: DeveloperRole) {
        redis.sadd(listOf("roles:${role.roleName}:access_permissions", id)).await()
    }

    suspend fun removeAccessPermissionFromRole(id: String, role: DeveloperRole) {
        redis.srem(listOf("roles:${role.roleName}:access_permissions", id)).await()
    }

    suspend fun getDeveloperDataRedactions(developerId: String): List<DataRedaction> {
        return getDeveloperRoles(developerId).flatMap { getRoleDataRedactions(it) }
    }

    suspend fun getDataRedactions(): Set<DataRedaction> {
        val roles = redis.smembers("data_redactions").await()
        return roles.map { getDataRedaction(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    suspend fun hasDataRedaction(id: String): Boolean {
        return redis.sismember("data_redactions", id).await().toBoolean()
    }

    suspend fun getDataRedaction(id: String): DataRedaction {
        val permission = redis.get("data_redactions:$id").await()
        return DataRedaction(
            id,
            permission.toString(StandardCharsets.UTF_8)
        )
    }

    suspend fun addDataRedaction(id: String, redactionPattern: String) {
        redis.sadd(listOf("data_redactions", id)).await()
        redis.set(listOf("data_redactions:$id", redactionPattern)).await()
    }

    suspend fun removeDataRedaction(id: String) {
        getRoles().forEach {
            removeDataRedactionFromRole(id, it)
        }
        redis.srem(listOf("data_redactions", id)).await()
        redis.del(listOf("data_redactions:$id")).await()
    }

    suspend fun addDataRedactionToRole(id: String, role: DeveloperRole) {
        redis.sadd(listOf("roles:${role.roleName}:data_redactions", id)).await()
    }

    suspend fun removeDataRedactionFromRole(id: String, role: DeveloperRole) {
        redis.srem(listOf("roles:${role.roleName}:data_redactions", id)).await()
    }

    suspend fun getRoleDataRedactions(role: DeveloperRole): Set<DataRedaction> {
        val dataRedactions = redis.smembers("roles:${role.roleName}:data_redactions").await()
        return dataRedactions.map { getDataRedaction(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    suspend fun getRoles(): Set<DeveloperRole> {
        val roles = redis.smembers("roles").await()
        return roles.map { DeveloperRole.fromString(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    suspend fun addRoleToDeveloper(id: String, role: DeveloperRole) {
        redis.sadd(listOf("developers:$id:roles", role.roleName)).await()
    }

    suspend fun removeRoleFromDeveloper(id: String, role: DeveloperRole) {
        redis.srem(listOf("developers:$id:roles", role.roleName)).await()
    }

    suspend fun addPermissionToRole(role: DeveloperRole, permission: RolePermission) {
        redis.sadd(listOf("roles", role.roleName)).await()
        redis.sadd(listOf("roles:${role.roleName}:permissions", permission.name)).await()
    }

    suspend fun removePermissionFromRole(role: DeveloperRole, permission: RolePermission) {
        redis.srem(listOf("roles:${role.roleName}:permissions", permission.name)).await()
    }

    suspend fun getRolePermissions(role: DeveloperRole): Set<RolePermission> {
        val permissions = redis.smembers("roles:${role.roleName}:permissions").await()
        return permissions.map { RolePermission.valueOf(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    suspend fun getDeveloperPermissions(id: String): Set<RolePermission> {
        return getDeveloperRoles(id).flatMap { getRolePermissions(it) }.toSet()
    }

    suspend fun hasPermission(id: String, permission: RolePermission): Boolean {
        log.trace("Checking permission: $permission - Id: $id")
        return getDeveloperRoles(id).any { getRolePermissions(it).contains(permission) }
    }
}
