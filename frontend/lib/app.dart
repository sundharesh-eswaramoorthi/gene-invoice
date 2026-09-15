import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

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
