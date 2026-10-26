package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity

@Dao
interface WorkspaceDAO {
    @Query("SELECT * FROM workspaces ORDER BY updated_at DESC")
    fun listFlow(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun getById(id: String): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE root = :root")
    suspend fun getByRoot(root: String): WorkspaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workspace: WorkspaceEntity)

    @Query("SELECT * FROM workspaces")
    suspend fun getAll(): List<WorkspaceEntity>

    /** 某一类型当前被声明给 AI 的工作区；数据层保证每类最多一条 */
    @Query("SELECT * FROM workspaces WHERE declared = 1 AND type = :type LIMIT 1")
    suspend fun getDeclaredByType(type: String): WorkspaceEntity?

    /** 所有被声明的工作区：本地和 SSH 各最多一条 */
    @Query("SELECT * FROM workspaces WHERE declared = 1 ORDER BY type, updated_at DESC")
    fun declaredFlow(): Flow<List<WorkspaceEntity>>

    @Query("UPDATE workspaces SET declared = 0, updated_at = :updatedAt WHERE declared = 1 AND type = :type")
    suspend fun clearDeclaredByType(type: String, updatedAt: Long): Int

    @Query("UPDATE workspaces SET declared = 0, updated_at = :updatedAt WHERE id = :id")
    suspend fun clearDeclaredById(id: String, updatedAt: Long): Int

    @Query("UPDATE workspaces SET declared = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeclared(id: String, updatedAt: Long): Int

    /**
     * 把 [id] 设为所属类型里唯一被声明的工作区，同类型的其他工作区在同一事务里取消声明。
     *
     * 本地与 SSH 各允许声明一个，因此这里只清除同类型的声明。
     */
    @Transaction
    suspend fun setDeclared(id: String, updatedAt: Long) {
        val type = getById(id)?.type ?: return
        clearDeclaredByType(type, updatedAt)
        markDeclared(id, updatedAt)
    }

    @Query("UPDATE workspaces SET directory = :directory, updated_at = :updatedAt WHERE id = :id")
    suspend fun setDirectory(id: String, directory: String?, updatedAt: Long): Int

    @Query("UPDATE workspaces SET toolchain_enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setToolchainEnabled(id: String, enabled: Boolean, updatedAt: Long): Int

    /** 切换工作区类型并写入 SSH 连接参数（同一事务里改，避免出现「类型是 SSH 但没地址」的中间态） */
    @Query(
        "UPDATE workspaces SET type = :type, ssh_host = :host, ssh_port = :port, " +
            "ssh_username = :username, ssh_root = :root, updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun setSshConfig(
        id: String,
        type: String,
        host: String?,
        port: Int,
        username: String?,
        root: String?,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
