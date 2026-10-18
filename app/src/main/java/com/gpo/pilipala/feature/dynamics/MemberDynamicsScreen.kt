package com.gpo.pilipala.feature.dynamics

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.PiliTopBar
import com.gpo.pilipala.ui.components.RefreshBox

/** "XX的动态" page (Flutter pages/member_dynamics). */
@Composable
fun MemberDynamicsScreen(route: Route.MemberDynamics) {
    val vm = memberDynamicsViewModel(route.mid)
    val name = route.name.ifEmpty {
        // The member's own posts carry their name in the author module.
        vm.pager.items.firstOrNull { it.json.author.str("mid") == route.mid.toString() }?.json?.author.str("name")
    }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Scaffold(
        topBar = { PiliTopBar(if (name.isNullOrEmpty()) "动态" else "${name}的动态") },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        RefreshBox(
            isRefreshing = vm.pager.isRefreshing && vm.pager.items.isNotEmpty(),
            onRefresh = {
                VoteStore.invalidate()
                vm.pager.refresh()
            },
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            MemberDynamicsList(
                mid = route.mid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = navBottom + 24.dp),
            )
        }
    }
}
