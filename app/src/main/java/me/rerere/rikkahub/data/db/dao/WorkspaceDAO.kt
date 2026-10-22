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

    /** 当前被声明给 AI 的工作区；数据层保证最多一条 */
    @Query("SELECT * FROM workspaces WHERE declared = 1 LIMIT 1")
    suspend fun getDeclared(): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE declared = 1 LIMIT 1")
    fun declaredFlow(): Flow<WorkspaceEntity?>

    @Query("UPDATE workspaces SET declared = 0, updated_at = :updatedAt WHERE declared = 1")
    suspend fun clearDeclared(updatedAt: Long): Int

    @Query("UPDATE workspaces SET declared = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeclared(id: String, updatedAt: Long): Int

    /**
     * 把 [id] 设为唯一被声明的工作区，其余工作区在同一事务里取消声明。
     *
     * 互斥由数据层保证，不依赖 UI 顺序。
     */
    @Transaction
    suspend fun setDeclared(id: String, updatedAt: Long) {
        clearDeclared(updatedAt)
        markDeclared(id, updatedAt)
    }

    @Query("UPDATE workspaces SET directory = :directory, updated_at = :updatedAt WHERE id = :id")
    suspend fun setDirectory(id: String, directory: String?, updatedAt: Long): Int

    @Query("UPDATE workspaces SET toolchain_enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setToolchainEnabled(id: String, enabled: Boolean, updatedAt: Long): Int

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
