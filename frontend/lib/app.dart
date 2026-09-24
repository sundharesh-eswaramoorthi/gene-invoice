import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core/as_of/as_of_providers.dart';
import 'core/router.dart';
import 'core/table/table_providers.dart';
import 'core/theme.dart';
import 'features/auth/auth_controller.dart';

class GeneInvoiceApp extends ConsumerStatefulWidget {
  const GeneInvoiceApp({super.key});
  @override
  ConsumerState<GeneInvoiceApp> createState() => _GeneInvoiceAppState();
}

class _GeneInvoiceAppState extends ConsumerState<GeneInvoiceApp> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(authControllerProvider.notifier).bootstrap();
      ref.read(pageSizeStoreProvider.notifier).hydrate();
      // How far back this installation can answer, asked once and kept. Without it the first date
      // picker to open has no floor to bound itself with and would offer a date the server
      // refuses. It watches the signed-in person, so it fetches again for real once bootstrap
      // finishes rather than answering "nothing is as-of capable" for ever (B3).
      ref.read(asOfCapabilityProvider);
    });
  }

  @override
  Widget build(BuildContext context) {
    final router = ref.watch(routerProvider);
    return MaterialApp.router(
      title: 'Gene Invoice',
      theme: AppTheme.light(),
      routerConfig: router,
      debugShowCheckedModeBanner: false,
    );
  }
}
