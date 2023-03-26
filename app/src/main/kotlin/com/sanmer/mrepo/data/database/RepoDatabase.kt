package com.sanmer.mrepo.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sanmer.mrepo.data.database.dao.RepoDao
import com.sanmer.mrepo.data.database.entity.OnlineModuleEntity
import com.sanmer.mrepo.data.database.entity.Repo

@Database(entities = [Repo::class, OnlineModuleEntity::class], version = 2)
abstract class RepoDatabase : RoomDatabase() {
    abstract fun repoDao(): RepoDao

    companion object {
        private var instance: RepoDatabase? = null

        @Synchronized
        fun getDatabase(context: Context): RepoDatabase {
            instance?.let {
                return it
            }

            return Room.databaseBuilder(context.applicationContext,
                RepoDatabase::class.java, "repo")
                .addMigrations(MIGRATION_1_2)
                .build().apply {
                    instance = this
                }
        }

        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS repo_new (" +
                        "url TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "size INTEGER NOT NULL, " +
                        "timestamp REAL NOT NULL, " +
                        "enable INTEGER NOT NULL, " +
                        "PRIMARY KEY(url))")
                database.execSQL("INSERT INTO repo_new (" +
                        "url, name, size, timestamp, enable) " +
                        "SELECT " +
                        "url, name, size, timestamp, enable " +
                        "FROM repo")
                database.execSQL("DROP TABLE repo")
                database.execSQL("ALTER TABLE repo_new RENAME TO repo")

                database.execSQL("CREATE TABLE IF NOT EXISTS online_module (" +
                        "id TEXT NOT NULL, " +
                        "repo_url TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "version TEXT NOT NULL, " +
                        "version_code INTEGER NOT NULL, " +
                        "author TEXT NOT NULL, " +
                        "description TEXT NOT NULL, " +
                        "license TEXT NOT NULL, " +
                        "zipUrl TEXT NOT NULL, " +
                        "changelog TEXT NOT NULL, " +
                        "PRIMARY KEY(id, repo_url))")
            }

        }
    }
}