package io.github.conichonhaa.ilo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.conichonhaa.ilo.ui.console.ConsoleScreen
import io.github.conichonhaa.ilo.ui.detail.ServerDetailScreen
import io.github.conichonhaa.ilo.ui.servers.EditServerScreen
import io.github.conichonhaa.ilo.ui.servers.ServerListScreen
import io.github.conichonhaa.ilo.ui.theme.IloTheme
import io.github.conichonhaa.ilo.ui.web.WebScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repository = (application as IloApp).repository
        setContent {
            IloTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "servers") {
                    composable("servers") {
                        ServerListScreen(
                            repository = repository,
                            onOpen = { nav.navigate("detail/$it") },
                            onAdd = { nav.navigate("edit") },
                        )
                    }
                    composable(
                        "edit?id={id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true }),
                    ) { entry ->
                        EditServerScreen(
                            repository = repository,
                            serverId = entry.arguments?.getString("id"),
                            onDone = { nav.popBackStack() },
                        )
                    }
                    composable("detail/{id}") { entry ->
                        val id = entry.arguments?.getString("id").orEmpty()
                        ServerDetailScreen(
                            repository = repository,
                            serverId = id,
                            onBack = { nav.popBackStack() },
                            onEdit = { nav.navigate("edit?id=$id") },
                            onConsole = { nav.navigate("console/$id") },
                            onWeb = { nav.navigate("web/$id") },
                        )
                    }
                    composable("console/{id}") { entry ->
                        ConsoleScreen(
                            repository = repository,
                            serverId = entry.arguments?.getString("id").orEmpty(),
                            onExit = { nav.popBackStack() },
                        )
                    }
                    composable("web/{id}") { entry ->
                        WebScreen(
                            repository = repository,
                            serverId = entry.arguments?.getString("id").orEmpty(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
